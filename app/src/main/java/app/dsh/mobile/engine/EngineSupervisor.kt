package app.dsh.mobile.engine

import app.dsh.mobile.R

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 引擎监督器：冷启动 → 健康检查 → 运行中 → 崩溃退避重启 的完整状态机。
 *
 * 状态流转：
 *   Idle → Installing → Starting → Healthy(port)
 *        ↘ Backoff(delay, attempt) ↘ Failed(reason) → Stopped
 * 连续健康一次即重置退避计数；达到 MAX_RESTART 后进入 Failed 终态。
 *
 * 自愈层（ProfileGuardian）： Healthy 时快照配置；同签名连续失败触发
 * last-good 回滚；仍失败进入安全模式（归档坏配置空跑）。
 * SafeMode 会作为独立状态暴露给 UI 展示「引擎运行于安全模式」。
 */
class EngineSupervisor(private val ctx: Context) {

    sealed interface State {
        data object Idle : State
        data object Installing : State
        data object Starting : State

        /** 正常健康；tokenUrl = 0.2.0+ WebUI 认证地址（engine.log 的 dsh web: 输出） */
        data class Healthy(
            val port: Int,
            val tokenUrl: String? = null,
            /** 本次启动前自动摘掉的第三方插件；仅在恢复后的首个 Healthy 状态携带。 */
            val recoveredPlugin: String? = null,
        ) : State

        /** 安全模式：配置被隔离后以空配置拉起，功能受限但可用 */
        data class SafeMode(
            val port: Int,
            val tokenUrl: String? = null,
            val recoveredPlugin: String? = null,
        ) : State
        data class Backoff(val delayMs: Long, val attempt: Int) : State
        data class Failed(val reason: String) : State
        data object Stopped : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** 运行时解压进度 0f..1f；null = 不在解压中（UI 据此切换 indeterminate） */
    private val _installProgress = MutableStateFlow<Float?>(null)
    val installProgress: StateFlow<Float?> = _installProgress

    /** 当前健康端口，UI 层据此加载 WebView */
    val healthyPort: Int get() = when (val s = _state.value) {
        is State.Healthy -> s.port
        is State.SafeMode -> s.port
        else -> EngineConfig.DEFAULT_PORT
    }

    /** 从 engine.log 提取**本次启动**的 `dsh web:` 输出（带 token 完整 URL）。
     *  0.2.0 起 WebUI 强制认证，裸 127.0.0.1:3080 会显示认证提示页。
     *  引擎 stdout 由 EngineProcess 泵入 engine.log（环形 2MB，最新在末尾）。
     *  只搜 [logOffsetAtSpawn] 之后的日志段：健康探测可能早于 stdout 送达，
     *  搜全文会拿到上一次启动的旧 token（已失效 → WebView 401 白页，实测）。
     *  stdout 泵有毫秒级延迟，最多重试 ~600ms 等该行出现。 */
    private fun extractTokenUrl(): String? = runCatching {
        repeat(6) {
            val f = logFile()
            if (f.isFile) {
                val seg = runCatching {
                    java.io.RandomAccessFile(f, "r").use { raf ->
                        val start = logOffsetAtSpawn.coerceIn(0L, raf.length())
                        raf.seek(start)
                        val buf = ByteArray((raf.length() - start).coerceAtMost(1L shl 20).toInt())
                        var read = 0
                        while (read < buf.size) {
                            val n = raf.read(buf, read, buf.size - read)
                            if (n <= 0) break
                            read += n
                        }
                        String(buf, 0, read, Charsets.UTF_8)
                    }
                }.getOrNull().orEmpty()
                Regex("dsh web: (http://127\\.0\\.0\\.1:\\d+/\\?token=\\S+)")
                    .findAll(seg).lastOrNull()?.groupValues?.get(1)
                    ?.let { hit -> return@runCatching hit }
            }
            Thread.sleep(100)
        }
        null
    }.getOrNull()

    /** WebUI 应加载的地址：优先带 token 的完整 URL，裸地址仅作回退 */
    fun webUrl(): String = when (val s = _state.value) {
        is State.Healthy -> s.tokenUrl ?: "http://127.0.0.1:${s.port}/"
        is State.SafeMode -> s.tokenUrl ?: "http://127.0.0.1:${s.port}/"
        else -> "http://127.0.0.1:${EngineConfig.DEFAULT_PORT}/"
    }

    @Volatile private var process: EngineProcess? = null
    @Volatile private var recoveredPlugin: String? = null
    @Volatile private var logOffsetAtSpawn = 0L
    @Volatile private var loopJob: Job? = null
    @Volatile private var userStop = false

    @Volatile
    private var stopping = false
    @Volatile private var scopeRef: CoroutineScope? = null

    fun isUserStopped(): Boolean = userStop && (loopJob?.isActive != true)

    @Volatile var onExplicitExit: (() -> Unit)? = null

    private fun dispatchExplicitExit() {
        val cb = onExplicitExit
        if (cb == null) {
            Log.i(TAG, "explicit exit: no live Activity; killing process instead")
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (cb != null) {
                runCatching { cb() }
                    .onFailure { Log.w(TAG, "explicit exit callback failed: ${it.message}") }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                killAppProcess()
            }, if (cb == null) 0L else 600L)
        }
    }

    private fun killAppProcess() {
        Log.i(TAG, "explicit exit: finishing app process")
        runCatching {
            android.app.NotificationManager::class.java
                .let { ctx.getSystemService(it) }
                ?.cancelAll()
        }
        runCatching {
            android.os.Process.killProcess(android.os.Process.myPid())
        }.onFailure {
            Log.w(TAG, "killProcess failed (${it.message}); falling back to exit")
            kotlin.system.exitProcess(0)
        }
    }

    @Volatile private var restarting = false
    @Volatile private var restartStartedAt: Long? = null
    private val guardian by lazy { ProfileGuardian(ctx) }

    fun start(scope: CoroutineScope) {
        scopeRef = scope
        if (loopJob?.isActive == true) return
        userStop = false
        stopping = false
        loopJob = scope.launch(Dispatchers.Default) { supervisionLoop() }
    }

    fun stop(graceMs: Long = 10_000, explicit: Boolean = false) {
        if (explicit) userStop = true
        stopping = true
        loopJob?.cancel()
        process?.stop(graceMs)
        process = null
        runCatching { cleanupOrphanEngine() }
        if (explicit) {
            runCatching {
                val killed = OrphanReaper.reap(ctx)
                if (killed > 0) Log.i(TAG, "explicit exit: reaped $killed orphan process(es)")
            }.onFailure { Log.w(TAG, "explicit exit orphan reap failed: ${it.message}") }
        }
        _state.value = State.Stopped
        if (explicit) dispatchExplicitExit()
    }

    fun restart() {
        val scope = scopeRef ?: return
        if (restarting) {
            Log.i(TAG, "restart already in progress, ignoring duplicate tap")
            return
        }
        Log.i(TAG, "restart: hot restart requested by user")
        restarting = true
        val t0 = System.currentTimeMillis()
        restartStartedAt = t0
        scope.launch(Dispatchers.IO) {
            try {
                _state.value = State.Starting
                stop(graceMs = 3_500)
                Log.i(TAG, "restart: previous engine stopped (${System.currentTimeMillis() - t0}ms)")
                _state.value = State.Starting
                start(scope)
            } catch (e: Exception) {
                Log.w(TAG, "restart failed: ${e.message}")
            } finally {
                restarting = false
            }
        }
    }

    fun logFile(): File = File(EngineConfig.engineRoot(ctx), "engine.log")

    private fun failureSignature(status: Int?): String {
        val tail = runCatching {
            logFile().readText().takeLast(4096)
                .lineSequence()
                .filter { it.contains("Error") || it.contains("at ") }
                .toList()
                .takeLast(12)
                .joinToString("\n") { it.replace(Regex("\\d+"), "#") }
        }.getOrDefault("")
        return "$status:${tail.hashCode()}"
    }

    private fun looksLikePortConflict(): Boolean = runCatching {
        val f = logFile()
        if (!f.isFile) return@runCatching false
        val len = f.length()
        val from = logOffsetAtSpawn.coerceIn(0L, len)
        val tail = java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(maxOf(from, len - 8192))
            val buf = ByteArray((len - maxOf(from, len - 8192)).toInt())
            raf.readFully(buf)
            String(buf, Charsets.UTF_8)
        }
        tail.contains("EADDRINUSE") || tail.contains("address already in use", ignoreCase = true)
    }.getOrDefault(false)

    private suspend fun shouldExitLoop(): Boolean =
        stopping || !kotlinx.coroutines.currentCoroutineContext().isActive

    private suspend fun supervisionLoop() {
        var backoffIndex = 0
        var portReclaimCount = 0
        var totalPortReclaims = 0
        var pluginRecoveryRequestedThisLoop = false
        var pluginRecoveryRestarts = 0
        var fastIsolations = 0
        var userVisibleBootT0 = System.currentTimeMillis()
        while (kotlinx.coroutines.currentCoroutineContext().isActive && !stopping) {
            val bootElapsed = System.currentTimeMillis() - userVisibleBootT0
            if (bootElapsed > EngineConfig.BOOT_WALL_CLOCK_BUDGET_MS && _state.value !is State.Healthy &&
                _state.value !is State.SafeMode
            ) {
                val mins = bootElapsed / 60_000
                Log.e(
                    TAG,
                    "boot exceeded ${EngineConfig.BOOT_WALL_CLOCK_BUDGET_MS}ms budget " +
                        "(elapsed=${bootElapsed}ms, attempts=$backoffIndex, " +
                        "pluginRecoveryRestarts=$pluginRecoveryRestarts) — giving up with a visible error",
                )
                _state.value = State.Failed(
                    ctx.getString(R.string.error_boot_timeout, mins),
                )
                BootTrace.fail(ctx, ctx.getString(R.string.error_boot_timeout, mins))
                return
            }
            var deterministicFailure: String? = null
            val bootT0 = System.currentTimeMillis()
            var stepT = bootT0
            BootTrace.begin(
                when {
                    pluginRecoveryRequestedThisLoop -> "plugin recovery restart"
                    backoffIndex > 0 -> "retry #${backoffIndex + 1}"
                    restartStartedAt != null -> "user restart"
                    else -> "cold start"
                },
            )
            pluginRecoveryRequestedThisLoop = false
            fun step(name: String) {
                val now = System.currentTimeMillis()
                Log.i(TAG, "boot step $name: ${now - stepT}ms (total ${now - bootT0}ms)")
                BootTrace.step(name)
                stepT = now
            }
            try {
                val healed = withContext(Dispatchers.IO) { guardian.restoreQuarantinedBuiltinOverlays() }
                step("guardian-restore")
                if (healed > 0) Log.w(TAG, "guardian: restored $healed quarantined builtin overlay(s)")

                // 孤儿进程回收（v1.2.104）：必须早于桥 bind 与引擎 fork
                withContext(Dispatchers.IO) { OrphanReaper.reap(ctx) }
                step("orphan-reap")

                withContext(Dispatchers.IO) { AgentBridge.start(ctx) }
                step("bridge")

                val needFix = withContext(Dispatchers.IO) { Privilege.dshHomeNeedsOwnershipFix(ctx) }
                if (needFix) {
                    Log.w(TAG, "found files with broken ownership/permissions (root residue or bad labels); healing")
                    val fixed = withContext(Dispatchers.IO) { Privilege.fixHomeOwnership(ctx) }
                    Log.w(TAG, "attribute healing ${if (fixed) "succeeded" else "unavailable (no shizuku/su)"}")
                }
                step("home-owner-check")

                _state.value = State.Installing
                BootTrace.stage(BootTrace.Stage.EXTRACTING)
                withContext(Dispatchers.IO) {
                    var lastPct = -1
                    val reused = RuntimeInstaller(ctx).ensureInstalled(
                        onProgress = { frac ->
                            val pct = (frac * 100).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                _installProgress.value = frac
                            }
                        },
                        onDetail = { detail ->
                            BootTrace.install(detail)
                        },
                    )
                    if (reused) BootTrace.markRuntimeReused()
                }
                _installProgress.value = null
                step("runtime-install")

                withContext(Dispatchers.IO) { EngineConfig.ensureUserDataExternal(ctx) }
                step("user-data")

                _state.value = State.Starting
                BootTrace.stage(BootTrace.Stage.STARTING)
                withContext(Dispatchers.IO) { PluginRecovery.prepareForBoot(ctx) }
                val bootId = java.util.UUID.randomUUID().toString()
                val proc = withContext(Dispatchers.IO) { spawnEngine(bootId) }
                process = proc
                step("spawn")

                val healthy = pollHealth(EngineConfig.DEFAULT_PORT, proc)
                step("health-poll")
                if (healthy) {
                    BootTrace.stage(BootTrace.Stage.LOADING)
                    var pluginRecoveryRequested = false
                    val health = withContext(Dispatchers.IO) { awaitPluginHealth(proc, bootId) }
                    if (health != null) {
                        val failedText =
                            health.failedEntries.joinToString(",") { "${it.id}:${it.name}" }
                        val summary =
                            "plugin health: ok=${health.ok} " +
                                "missing=[${health.missingServices.joinToString(",")}] " +
                                "failed=[$failedText]"
                        Log.i(TAG, summary)
                        runCatching { EngineLog.append(logFile(), "[health] " + summary) }
                    } else {
                        Log.w(TAG, "plugin health marker missing or unreadable; keeping engine available")
                        runCatching {
                            EngineLog.append(
                                logFile(),
                                "[health] marker missing/unreadable (probe did not report)",
                            )
                        }
                    }
                    if (health?.ok == true) {
                        withContext(Dispatchers.IO) { PluginRecovery.onHealthy(ctx) }
                    }
                    if (health != null && !health.ok) {
                        val action = withContext(Dispatchers.IO) { PluginRecovery.quarantineOne(ctx, health) }
                        if (action != null) {
                            recoveredPlugin = action.packageName
                            pluginRecoveryRequested = true
                            pluginRecoveryRequestedThisLoop = true
                            Log.e(
                                TAG,
                                "plugin recovery: disabled ${action.packageName}; reason=${action.reason}; " +
                                    "restart=${action.attempt}/${3}",
                            )
                            proc.stop(graceMs = 1_500)
                            deterministicFailure = null
                            pluginRecoveryRestarts++
                        } else {
                            Log.e(TAG, "plugin recovery: no safe candidate; leaving profile unchanged")
                        }
                    }
                    if (pluginRecoveryRequested) {
                        if (pluginRecoveryRestarts > EngineConfig.MAX_PLUGIN_RECOVERY_RESTARTS) {
                            Log.e(
                                TAG,
                                "plugin recovery restart budget exhausted " +
                                    "(${EngineConfig.MAX_PLUGIN_RECOVERY_RESTARTS} lifetime) — " +
                                    "leaving degraded engine available instead of looping",
                            )
                            pluginRecoveryRequested = false
                        }
                    } else {
                        if (health?.ok != false) {
                            withContext(Dispatchers.IO) {
                                guardian.resetCrashStreak()
                                guardian.snapshotLastGood()
                            }
                        } else {
                            Log.w(TAG, "plugin health degraded; keeping previous last-good snapshot")
                        }
                        backoffIndex = 0
                        app.dsh.mobile.engine.ExtensionManager.clearPendingRestart()
                        val safe = guardian.inSafeMode()
                        val tokenUrl = extractTokenUrl()
                        val notice = recoveredPlugin
                        recoveredPlugin = null
                        val t1 = System.currentTimeMillis()
                        val restartMs = restartStartedAt?.let { " (total restart took ${t1 - it}ms)" } ?: ""
                        restartStartedAt = null
                        Log.i(TAG, (if (safe) "engine healthy in SAFE MODE on :${EngineConfig.DEFAULT_PORT}" else "engine healthy on :${EngineConfig.DEFAULT_PORT}") + restartMs)
                        _state.value =
                            if (safe) State.SafeMode(EngineConfig.DEFAULT_PORT, tokenUrl, notice)
                            else State.Healthy(EngineConfig.DEFAULT_PORT, tokenUrl, notice)
                        BootTrace.ready(ctx)
                        userVisibleBootT0 = System.currentTimeMillis()
                        pluginRecoveryRestarts = 0
                        backoffIndex = 0
                        withContext(Dispatchers.IO) {
                            ShizukuHttpBridge.start(ctx, EngineConfig.DEFAULT_PORT)
                        }
                        val status = proc.exitFuture.get()
                        if (shouldExitLoop()) break
                        Log.w(TAG, "engine exited, raw status=0x${status.toString(16)}")
                        userVisibleBootT0 = System.currentTimeMillis()
                    }
                } else if (proc.exitFuture.isDone) {
                    deterministicFailure = failureSignature(lastExitStatus)
                } else {
                    proc.stop()
                }
            } catch (e: EngineStartException) {
                if (shouldExitLoop()) break
                Log.e(TAG, "supervision failure", e)
                BootTrace.fail(ctx, e.message ?: e.javaClass.simpleName)
                if (e.message?.contains("no usable su") == true) {
                    Log.w(TAG, "su unavailable right now — treating as transient, not a config failure")
                    deterministicFailure = null
                } else {
                    deterministicFailure = failureSignature(null)
                }
            } catch (e: EngineArchMismatchException) {
                Log.e(TAG, "runtime architecture mismatch — stopping (retrying cannot help)", e)
                _state.value = State.Failed(ctx.getString(R.string.error_runtime_arch_mismatch))
                BootTrace.fail(ctx, ctx.getString(R.string.error_runtime_arch_mismatch))
                return
            } catch (e: Exception) {
                if (shouldExitLoop()) break
                Log.e(TAG, "supervision failure", e)
                BootTrace.fail(ctx, e.message ?: e.javaClass.simpleName)
            }

            val portEvidence = withContext(Dispatchers.IO) {
                portInUse() && (deterministicFailure != null || looksLikePortConflict())
            }
            if (portEvidence) {
                totalPortReclaims++
                if (totalPortReclaims <= EngineConfig.MAX_PORT_RECLAIMS) {
                    portReclaimCount++
                    withContext(Dispatchers.IO) { reclaimPort() }
                    deterministicFailure = null
                    delay(1_200)
                } else {
                    Log.e(
                        TAG,
                        "port reclaim exhausted (${EngineConfig.MAX_PORT_RECLAIMS} lifetime attempts) — " +
                            "port ${EngineConfig.DEFAULT_PORT} is held by a foreign process",
                    )
                    _state.value = State.Failed(
                        "port ${EngineConfig.DEFAULT_PORT} is in use by another process",
                    )
                    break
                }
            }
            deterministicFailure?.let { sig ->
                if (backoffIndex + 1 >= PluginRecovery.FAST_ISOLATE_STREAK &&
                    fastIsolations < PluginRecovery.MAX_FAST_ISOLATIONS
                ) {
                    val isolated = runCatching {
                        PluginRecovery.isolateRecentBundleOnBootFailure(ctx)
                    }.getOrNull()
                    if (isolated != null) {
                        fastIsolations++
                        recoveredPlugin = isolated.packageName
                        Log.e(
                            TAG,
                            "boot failure: isolated recently installed plugin " +
                                "${isolated.packageName} (reason=${isolated.reason})",
                        )
                        runCatching {
                            EngineLog.append(
                                logFile(),
                                "[plugin] isolated ${isolated.packageName} after " +
                                    "${backoffIndex + 1} failed boot(s) — engine could not start",
                            )
                        }
                        BootTrace.fail(ctx, "isolated plugin ${isolated.packageName}")
                    }
                }
                when (
                    runCatching { guardian.onFailure(sig) }
                        .getOrElse { ProfileGuardian.Action.NONE }
                ) {
                    ProfileGuardian.Action.ROLLED_BACK -> {
                        Log.w(TAG, "guardian: rolled back profiles to last-good")
                        backoffIndex = 0
                    }
                    ProfileGuardian.Action.SAFE_MODE -> {
                        Log.e(TAG, "guardian: verified persistent crash, entering SAFE MODE")
                        backoffIndex = 0
                    }
                    ProfileGuardian.Action.NONE -> {}
                }
            }

            backoffIndex++
            if (backoffIndex > EngineConfig.MAX_RESTART) {
                _state.value = State.Failed(ctx.getString(R.string.error_restart_failed_streak, EngineConfig.MAX_RESTART))
                return
            }
            val delayMs = EngineConfig.BACKOFF_STEPS[
                (backoffIndex - 1).coerceAtMost(EngineConfig.BACKOFF_STEPS.size - 1)
            ]
            _state.value = State.Backoff(delayMs, backoffIndex)
            delay(delayMs)
        }
    }

    private suspend fun awaitPluginHealth(
        proc: EngineProcess,
        bootId: String,
    ): PluginRecovery.Health? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline && !proc.exitFuture.isDone) {
            val health = PluginRecovery.readHealth(ctx, bootId)
            if (health != null) {
                Log.i(TAG, "plugin health marker received (ok=${health.ok})")
                return@withContext health
            }
            Thread.sleep(200L)
        }
        null
    }

    private val lastExitStatus: Int?
        get() = process?.exitFuture?.takeIf { it.isDone }?.get()

    private fun spawnEngine(bootId: String): EngineProcess {
        cleanupOrphanEngine()
        logOffsetAtSpawn = runCatching { logFile().length() }.getOrDefault(0L)
        val mode = Privilege.getMode(ctx)
        var suPath: String? = null
        if (mode == PrivMode.ROOT) {
            val backup = Privilege.backupHome(ctx)
            Log.w(TAG, if (backup != null) "ROOT mode: dsh-home backed up to $backup"
                  else "ROOT mode: WARNING — dsh-home backup failed")
            suPath = Privilege.findSu() ?: throw EngineStartException(
                ctx.getString(R.string.error_root_no_su)
            )
        }
        runCatching { EngineConfig.healTermuxShebangs(ctx) }
        val patchPaths = ArrayList<String>(2)
        runCatching { EngineConfig.ensureBundledSshPlugin(ctx)?.absolutePath }
            .getOrNull()?.let { patchPaths.add(it) }
        runCatching { EngineConfig.ensureAndroidOverlay(ctx).absolutePath }
            .getOrNull()?.let { patchPaths.add(it) }
        return EngineProcess.spawn(
            nodeBin = EngineConfig.nodeBin(ctx),
            entryJs = EngineConfig.dshEntry(ctx),
            cwd = EngineConfig.workspaces(ctx),
            env = EngineConfig.buildEnv(ctx, EngineConfig.DEFAULT_PORT, bootId),
            logFile = logFile(),
            suPath = suPath,
            patchPaths = patchPaths,
        )
    }

    private fun portInUse(timeoutMs: Int = 700): Boolean {
        return try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", EngineConfig.DEFAULT_PORT), timeoutMs)
                true
            }
        } catch (e: java.net.ConnectException) {
            false
        } catch (e: Exception) {
            true
        }
    }

    private fun reclaimPort() {
        Log.w(TAG, "port ${EngineConfig.DEFAULT_PORT} in use — reclaiming (orphan engine node)")
        val d = "$"
        val killCmd =
            "for p in " + d + "(ls /proc | grep -E '^[0-9]+" + d + "'); do " +
                "c=" + d + "(tr '\\0' ' ' < /proc/" + d + "p/cmdline 2>/dev/null) || continue; " +
                "case \"" + d + "c\" in *files/engine/bin/node*) " +
                "case \"" + d + "c\" in *lan-web-proxy*) continue;; esac; " +
                "kill -9 " + d + "p 2>/dev/null;; esac; done; true"
        runCatching {
            val p = ProcessBuilder("/system/bin/sh", "-c", killCmd).start()
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                runCatching { p.destroy() }
                Log.w(TAG, "reclaimPort: same-uid sweep timed out (3s) — continuing")
            }
        }
        Privilege.runSu(killCmd)
        waitPortFree(1_800)
        if (portInUse(400)) {
            Log.w(TAG, "port still in use after first sweep — retrying via su")
            Privilege.runSu(killCmd)
            waitPortFree(1_800)
        }
        if (portInUse(300)) {
            Log.e(TAG, "port ${EngineConfig.DEFAULT_PORT} STILL in use after sweeps — next boot will likely fail")
        }
    }

    private fun waitPortFree(budgetMs: Long): Boolean {
        val until = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < until) {
            if (!portInUse(250)) return true
            Thread.sleep(120)
        }
        return !portInUse(250)
    }

    private fun cleanupOrphanEngine() {
        if (!portInUse()) return
        reclaimPort()
    }

    private suspend fun awaitStable(proc: EngineProcess, windowMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            if (proc.exitFuture.isDone) return false
            delay(250)
        }
        return !proc.exitFuture.isDone
    }

    private suspend fun pollHealth(port: Int, proc: EngineProcess): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + EngineConfig.HEALTH_TIMEOUT_MS
        val url = "http://127.0.0.1:$port/"
        while (System.currentTimeMillis() < deadline &&
            kotlinx.coroutines.currentCoroutineContext().isActive
        ) {
            if (proc.exitFuture.isDone) return@withContext false
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 2_000
                conn.readTimeout = 2_000
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..499) return@withContext true
            } catch (_: Exception) {
            }
            Thread.sleep(250L)
        }
        false
    }

    companion object {
        private const val TAG = "EngineSupervisor"
    }
}
