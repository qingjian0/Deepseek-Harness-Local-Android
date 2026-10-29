package dsh.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * 第三方插件应急总开关（v1.4.27）。
 *
 * ## 为什么需要它
 *
 * 现有的三层自救都覆盖不到一类最难受的故障：**插件装完，引擎照常启动、4080 通了、
 * 探针也报 ok，但 WebUI 白屏 / 对话发不出去 / 一点就卡**。此时
 * `PluginRecovery.quarantineOne` 要求「探针 degraded + 失败 entry 精确归因」，
 * `isolateRecentBundleOnBootFailure` 要求「引擎压根起不来」，`ProfileGuardian`
 * 要求「同签名真死 10 次」——一个都不触发；而用户正被困在那个打不开的 WebUI 里，
 * 连「去插件中心卸载」都点不到。
 *
 * 本类提供的是**用户显式选择**的逃生门：把全部第三方 bundle 从加载列表里摘掉，
 * 只留引擎自带。插件文件、`dependencies`、lockfile 一律不动 —— 恢复只是把数组
 * 写回去，零下载。
 *
 * ## 为什么是「意图日志 + 每次启动收敛」
 *
 * manifest（`profiles/web/package.json`）**只在引擎没跑的时候改**才安全：引擎退出前
 * 会落盘自己的 profile 状态，运行中改会被覆盖回去；而「先停再改再起」中间的强杀
 * 窗口又会导致半改状态。这里沿用 [RuntimeInstaller] 换装的思路：动作只写一份意图
 * 日志（原子写），真正的 manifest 改写放在**每次 spawn 之前**（`enforceBeforeBoot`），
 * 按日志把目标状态收敛过去。
 *
 * 三条硬保证：
 *  1. 日志先落盘、manifest 后收敛 —— 中间任何一步被杀，下次启动都会收敛到日志说的
 *     那个状态，不存在「半摘」；
 *  2. 收敛是幂等的：`disabled` 就再摘一遍，`enabling` 就再写回一遍，重复执行无副作用；
 *  3. 恢复只写回「`dependencies` 里还在 **且** 目录还在」的包 —— 把已卸载的包塞回
 *     bundles 会让引擎解析失败，等于亲手再造一次启动故障。
 *
 * ## 与其它自救层的关系
 *
 * 禁用期间 `PluginRecovery` 自然空转（`userBundles` 为空 → 早退），不需要改它；
 * 这里**不碰** `profile-guardian.meta`、**不写** `.safe-mode` —— 这是用户的显式选择，
 * 不该污染崩溃计数。
 */
object PluginKillSwitch {

    private const val TAG = "PluginKillSwitch"

    /** 意图日志（放 dsh-home 顶层：跟着用户数据走，卸载重装不丢） */
    const val LOG_NAME = ".plugin-killswitch.json"

    /**
     * 冷启动标志文件：连原生界面都点不动时的最后手段。
     * `touch` 之后杀进程重开，本次启动即按「禁用全部第三方插件」处理。
     */
    const val FORCE_FLAG_NAME = ".plugin-killswitch.force"

    enum class Phase(val wire: String) {
        /** 目标：只剩引擎自带 bundle */
        DISABLED("disabled"),

        /** 正在恢复：目标 = 日志里的原始列表（崩溃后重复执行同样收敛到「已恢复」） */
        ENABLING("enabling"),

        /** 已恢复（保留日志作为历史记录，便于排查「上次是谁被禁了」） */
        ENABLED("enabled"),
        ;

        companion object {
            fun of(raw: String?): Phase? = entries.firstOrNull { it.wire == raw }
        }
    }

    /**
     * @param bundles **完整的原始 bundle 列表**（内置 + 第三方，保持原顺序）——
     *   恢复要逐项写回，只存第三方的话顺序会全丢。
     * @param allow 停用期间**白名单**：这些第三方包仍然加载（v1.4.28 逐个恢复）。
     *   仅在 `phase=disabled` 时有意义；恢复（enabling/enabled）后忽略。
     * @param reason 触发原因，仅用于日志/诊断
     */
    data class State(
        val phase: Phase,
        val bundles: List<String>,
        val at: Long,
        val reason: String = "",
        val allow: List<String> = emptyList(),
    ) {
        fun userBundles(): List<String> = bundles.filter(PluginRecovery::isUserBundle)
    }

    /** 给 UI / HTTP 用的只读快照 */
    data class Status(
        /** 第三方插件当前是否被全部禁用 */
        val active: Boolean,
        /** 是否有过日志记录（active=false 但有记录 = 已恢复过） */
        val recorded: Boolean,
        /** 当前仍在加载列表里的第三方 bundle */
        val thirdParty: List<String>,
        /** 日志里记录的、被禁用的那批 */
        val disabled: List<String>,
        /** 停用期间白名单：单独恢复的第三方包（v1.4.28） */
        val allow: List<String>,
        /** manifest 里 bundle 总数（含内置） */
        val total: Int,
        val at: Long,
    )

    /**
     * @param changed 本次是否真的改写了 manifest（false = 已经是目标状态）
     * @param skipped 恢复时因为「已卸载/目录缺失」被跳过的包
     */
    data class Report(
        val phase: Phase?,
        val before: Int,
        val after: Int,
        val changed: Boolean,
        val skipped: List<String> = emptyList(),
        val error: String? = null,
    )

    data class Outcome(val ok: Boolean, val message: String)

    // ================= 文件位置 =================

    fun logFile(ctx: Context): File = File(EngineConfig.dshHome(ctx), LOG_NAME)

    fun forceFlagFile(ctx: Context): File = File(EngineConfig.dshHome(ctx), FORCE_FLAG_NAME)

    private fun profileDir(ctx: Context): File = File(EngineConfig.dshHome(ctx), "profiles/web")

    private fun manifestFile(ctx: Context): File = File(profileDir(ctx), "package.json")

    // ================= 状态读取 =================

    fun read(ctx: Context): State? = runCatching {
        val file = logFile(ctx)
        if (!file.isFile) return@runCatching null
        val json = JSONObject(file.readText())
        val phase = Phase.of(json.optString("phase")) ?: return@runCatching null
        val arr = json.optJSONArray("bundles") ?: JSONArray()
        val bundles = (0 until arr.length())
            .mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
        val allowArr = json.optJSONArray("allow") ?: JSONArray()
        val allow = (0 until allowArr.length())
            .mapNotNull { allowArr.optString(it).takeIf(String::isNotBlank) }
        State(
            phase = phase,
            bundles = bundles,
            at = json.optLong("at", 0L),
            reason = json.optString("reason", ""),
            allow = allow,
        )
    }.onFailure {
        // 日志坏了不能静默：否则会退化成「看不出插件被禁过」
        Log.w(TAG, "cannot read state: ${it.message}")
    }.getOrNull()

    fun isActive(ctx: Context): Boolean = read(ctx)?.phase == Phase.DISABLED

    /**
     * 是否处于「插件集合正在被改写」的窗口：已停用（disabled）或正在恢复
     * （enabling，引擎重启中）。
     *
     * 与 [isActive] 分开是因为两者语义不同：`isActive` 回答"插件现在是不是被关着"，
     * 用于 UI 显示「恢复」还是「停用」；`isBusy` 回答"现在能不能装/卸插件"。
     * 恢复中的窗口虽然不算 disabled，但此时 pnpm 正在写 node_modules、监督器随后
     * 还要按日志写回 bundles —— 这时候插进来一个安装，装完就会被写回动作丢掉。
     */
    fun isBusy(ctx: Context): Boolean = when (read(ctx)?.phase) {
        Phase.DISABLED, Phase.ENABLING -> true
        else -> false
    }

    /**
     * 是否处于「正在恢复」窗口（enabling，引擎重启中）。v1.4.28 起停用期间允许
     * 安装/更新插件（装了不加载，直到用户单独启用），所以安装守卫只拦这个窗口：
     * 那会儿 pnpm 正在写 node_modules、监督器随后要按日志写回 bundles，插进来
     * 一个安装会被写回动作丢掉。
     */
    fun isRestoring(ctx: Context): Boolean = read(ctx)?.phase == Phase.ENABLING

    fun status(ctx: Context): Status {
        val names = manifestBundles(ctx)
        val state = read(ctx)
        return Status(
            active = state?.phase == Phase.DISABLED,
            recorded = state != null,
            thirdParty = names.filter(PluginRecovery::isUserBundle),
            disabled = state?.userBundles().orEmpty(),
            allow = state?.allow.orEmpty().filter(PluginRecovery::isUserBundle),
            total = names.size,
            at = state?.at ?: 0L,
        )
    }

    private fun write(ctx: Context, state: State) {
        val json = JSONObject().apply {
            put("v", 1)
            put("phase", state.phase.wire)
            put("at", state.at)
            put("reason", state.reason)
            put("bundles", JSONArray(state.bundles))
            put("allow", JSONArray(state.allow))
            put("count", state.userBundles().size)
        }
        atomicWrite(logFile(ctx), json.toString(2) + "\n")
    }

    // ================= 两个用户动作 =================

    /**
     * 请求「禁用全部第三方插件」。
     *
     * 只写意图日志 + 删掉冷启动标志，**不碰 manifest** —— 真正的摘除发生在
     * 下一次 spawn 前的 [enforceBeforeBoot]。调用方随后重启引擎即可生效。
     *
     * @return ok=false 表示没有第三方插件可禁（此时不该打扰用户）
     */
    fun requestDisable(ctx: Context, reason: String = "user"): Outcome {
        // ⚠️ 已经禁用时**绝不能**重写日志：此时 manifest 里只剩内置 bundle，
        // 再抓一次「当前列表」当原始记录，用户就永远恢复不了第三方插件了。
        if (read(ctx)?.phase == Phase.DISABLED) {
            return Outcome(false, "already disabled")
        }
        val current = manifestBundles(ctx)
        if (current.isEmpty()) {
            return Outcome(false, "plugin profile not created yet")
        }
        val thirdParty = current.filter(PluginRecovery::isUserBundle)
        if (thirdParty.isEmpty()) {
            return Outcome(false, "no third-party plugin installed")
        }
        return runCatching {
            write(
                ctx,
                State(
                    phase = Phase.DISABLED,
                    bundles = current,
                    at = System.currentTimeMillis(),
                    reason = reason,
                    allow = emptyList(),
                ),
            )
            runCatching { forceFlagFile(ctx).delete() }
            Log.e(
                TAG,
                "disable-all requested: ${thirdParty.size} third-party bundle(s) will be dropped " +
                    "on next boot ($reason)",
            )
            Outcome(true, thirdParty.joinToString(", "))
        }.onFailure {
            Log.e(TAG, "failed to write disable intent: ${it.message}", it)
        }.getOrElse { Outcome(false, it.message ?: "write failed") }
    }

    /**
     * 请求「恢复第三方插件」——按日志里的原始列表逐项写回。
     *
     * 写 `enabling` 而不是直接 `enabled`：恢复的 manifest 改写同样在下一次 spawn
     * 前做，中途被杀时下一次启动会**再做一遍恢复**（幂等），而不是停在半恢复。
     * 引擎 healthy 后由 [onHealthy] 把相位推进到 `enabled`。
     */
    fun requestEnable(ctx: Context): Outcome {
        val state = read(ctx)
            ?: return Outcome(false, "nothing to restore (no record)")
        if (state.phase == Phase.ENABLED) {
            return Outcome(false, "plugins already restored")
        }
        val restored = state.userBundles()
        if (restored.isEmpty()) {
            return Outcome(false, "record holds no third-party plugin")
        }
        return runCatching {
            write(ctx, state.copy(phase = Phase.ENABLING, at = System.currentTimeMillis()))
            runCatching { forceFlagFile(ctx).delete() }
            Log.i(TAG, "restore requested: ${restored.size} third-party bundle(s)")
            Outcome(true, restored.joinToString(", "))
        }.onFailure {
            Log.e(TAG, "failed to write restore intent: ${it.message}", it)
        }.getOrElse { Outcome(false, it.message ?: "write failed") }
    }

    /** 引擎 healthy 后调用：把 `enabling` 推进到 `enabled`（其余相位不动） */
    fun onHealthy(ctx: Context) {
        val state = read(ctx) ?: return
        if (state.phase != Phase.ENABLING) return
        runCatching {
            write(ctx, state.copy(phase = Phase.ENABLED, at = System.currentTimeMillis()))
            Log.i(TAG, "restore confirmed: plugins are active again")
        }.onFailure { Log.w(TAG, "cannot mark restored: ${it.message}") }
    }

    // ================= 单个插件的白名单动作（v1.4.28） =================

    /**
     * 停用期间**单独恢复**一个第三方插件：加进白名单，下次 spawn 起会加载。
     *
     * 排查工作流的核心一步：全禁重启 → 更新/重装嫌疑插件 → 单独启用它 →
     * 好了再逐个恢复其余，不好就 [requestDisableOne] 摘回去。
     *
     * 只在 disabled 相位有效；enabling/enabled 相位下插件本来就全部在加载。
     */
    fun requestEnableOne(ctx: Context, pkg: String): Outcome {
        val state = read(ctx)
            ?: return Outcome(false, "kill switch has no record")
        if (state.phase != Phase.DISABLED) {
            return Outcome(false, "plugins are not disabled; $pkg already loads")
        }
        if (!PluginRecovery.isUserBundle(pkg)) {
            return Outcome(false, "$pkg is not a third-party plugin")
        }
        if (pkg !in state.userBundles()) {
            return Outcome(false, "$pkg was not turned off by the switch")
        }
        if (pkg in state.allow) {
            return Outcome(false, "$pkg is already enabled individually")
        }
        if (pkg !in installedPackages(ctx) || !bundleDirExists(ctx, pkg)) {
            return Outcome(false, "$pkg is not installed on disk")
        }
        return runCatching {
            write(ctx, state.copy(allow = state.allow + pkg, at = System.currentTimeMillis()))
            runCatching { forceFlagFile(ctx).delete() }
            Log.i(TAG, "enable-one: $pkg will load on next boot (allow=${state.allow + pkg})")
            Outcome(true, pkg)
        }.onFailure {
            Log.e(TAG, "enable-one $pkg failed: ${it.message}", it)
        }.getOrElse { Outcome(false, it.message ?: "write failed") }
    }

    /**
     * 停用期间把已单独恢复的插件再摘回去（从白名单移除），下次 spawn 生效。
     * 同样只在 disabled 相位有效。
     */
    fun requestDisableOne(ctx: Context, pkg: String): Outcome {
        val state = read(ctx)
            ?: return Outcome(false, "kill switch has no record")
        if (state.phase != Phase.DISABLED) {
            return Outcome(false, "plugins are not disabled")
        }
        if (pkg !in state.allow) {
            return Outcome(false, "$pkg is not individually enabled")
        }
        return runCatching {
            write(ctx, state.copy(allow = state.allow - pkg, at = System.currentTimeMillis()))
            runCatching { forceFlagFile(ctx).delete() }
            Log.i(TAG, "disable-one: $pkg will not load on next boot (allow=${state.allow - pkg})")
            Outcome(true, pkg)
        }.onFailure {
            Log.e(TAG, "disable-one $pkg failed: ${it.message}", it)
        }.getOrElse { Outcome(false, it.message ?: "write failed") }
    }

    // ================= 启动收敛（唯一改 manifest 的地方） =================

    /**
     * 每次 spawn 之前调用（引擎必定没在跑），把 manifest 收敛到日志说的状态。
     *
     * 幂等：已经是目标状态时不写文件。任何异常都只记日志、不抛 —— 插件管理出问题
     * 绝不能拖累引擎启动。
     */
    fun enforceBeforeBoot(ctx: Context): Report {
        return try {
            consumeForceFlag(ctx)
            val state = read(ctx) ?: return Report(null, 0, 0, false)
            if (state.phase == Phase.ENABLED) {
                // 已恢复：只清理可能残留的冷启动标志，不动 manifest
                return Report(state.phase, 0, 0, false)
            }
            converge(ctx, state)
        } catch (e: Exception) {
            Log.e(TAG, "enforceBeforeBoot failed: ${e.message}", e)
            Report(null, 0, 0, false, error = e.message)
        }
    }

    /**
     * 冷启动标志 → 转成一次 disable 意图。
     *
     * 只有「当前不是 disabled」时才需要抓一份当前 bundle 列表当原始记录；
     * 已经是 disabled 时标志只是冗余，删掉即可（不能覆盖掉原有记录，
     * 否则恢复目标会被写成空列表）。
     */
    private fun consumeForceFlag(ctx: Context) {
        val flag = forceFlagFile(ctx)
        if (!flag.isFile) return
        val current = read(ctx)
        if (current?.phase != Phase.DISABLED) {
            val bundles = manifestBundles(ctx)
            if (bundles.isNotEmpty()) {
                write(
                    ctx,
                    State(
                        phase = Phase.DISABLED,
                        bundles = bundles,
                        at = System.currentTimeMillis(),
                        reason = "force-flag",
                    ),
                )
                Log.e(TAG, "force flag consumed: disable-all armed for this boot")
            }
        }
        runCatching { flag.delete() }
            .onFailure { Log.w(TAG, "cannot delete force flag: ${it.message}") }
    }

    private fun converge(ctx: Context, state: State): Report {
        val file = manifestFile(ctx)
        if (!file.isFile) {
            Log.w(TAG, "web profile manifest missing; nothing to converge")
            return Report(state.phase, 0, 0, false)
        }
        val manifest = runCatching { JSONObject(file.readText()) }.getOrElse {
            Log.e(TAG, "manifest unparseable, left untouched: ${it.message}", it)
            return Report(state.phase, 0, 0, false, error = it.message)
        }
        val current = bundleNames(manifest)
        val skipped = mutableListOf<String>()
        val target = when (state.phase) {
            // v1.4.28：白名单里的第三方包保留（哪怕 manifest 里已被摘掉，也补回来）
            Phase.DISABLED -> disabledTarget(ctx, state.allow, current, skipped)
            Phase.ENABLING -> restoreTarget(ctx, state.bundles, current, skipped)
            Phase.ENABLED -> current
        }
        if (target == current) {
            if (skipped.isNotEmpty()) {
                Log.w(TAG, "restore: skipped uninstalled bundle(s) ${skipped.joinToString(",")}")
            }
            return Report(state.phase, current.size, current.size, false, skipped)
        }
        writeBundles(file, manifest, target)
        Log.e(
            TAG,
            "converged bundles ${current.size} -> ${target.size} (phase=${state.phase.wire}" +
                (if (skipped.isEmpty()) ")" else ", skipped=${skipped.joinToString(",")})"),
        )
        return Report(state.phase, current.size, target.size, true, skipped)
    }

    /**
     * 停用相位的目标 bundle 列表：摘掉白名单外的第三方包；白名单里的包若已在
     * manifest 里就原地保留（顺序不乱），不在就补到末尾（此前被摘过）。
     * 白名单包已被卸载时目标里不放（塞回去会让引擎解析失败），包名从白名单
     * 里也不删 —— 与恢复语义一致：卸载是明确的用户动作，日志保持历史。
     */
    private fun disabledTarget(
        ctx: Context,
        allow: List<String>,
        current: List<String>,
        skipped: MutableList<String>,
    ): List<String> {
        val installed = installedPackages(ctx)
        val kept = allow.filter(PluginRecovery::isUserBundle)
        val out = LinkedHashSet<String>()
        for (name in current) {
            if (PluginRecovery.isUserBundle(name) && name !in kept) continue
            out += name
        }
        for (name in kept) {
            if (name in out) continue
            if (name in installed && bundleDirExists(ctx, name)) out += name
            else skipped += name
        }
        return out.toList()
    }

    /**
     * 恢复目标 = 日志里的原始顺序，但**只保留还装着的包**；引擎在禁用期间新加的
     * 内置 bundle（升级运行时会出现）补在末尾，不会被我们写丢。
     */
    private fun restoreTarget(
        ctx: Context,
        saved: List<String>,
        current: List<String>,
        skipped: MutableList<String>,
    ): List<String> {
        val dependencies = installedPackages(ctx)
        val out = LinkedHashSet<String>()
        for (name in saved) {
            if (!PluginRecovery.isUserBundle(name)) {
                out += name
                continue
            }
            if (name in dependencies && bundleDirExists(ctx, name)) {
                out += name
            } else {
                skipped += name
            }
        }
        current.filter { !PluginRecovery.isUserBundle(it) && it !in out }.forEach { out += it }
        return out.toList()
    }

    /** package.json 的 dependencies —— 与 dsh 官方 plugin manager 的记录同源 */
    private fun installedPackages(ctx: Context): Set<String> = runCatching {
        val manifest = manifestFile(ctx)
        if (!manifest.isFile) return emptySet()
        val deps = JSONObject(manifest.readText()).optJSONObject("dependencies") ?: return emptySet()
        deps.keys().asSequence().toSet()
    }.getOrDefault(emptySet())

    /** bundle 目录存在性：profile 优先，内置 bundle 在 engine/lib/node_modules 下 */
    private fun bundleDirExists(ctx: Context, name: String): Boolean =
        File(File(profileDir(ctx), "node_modules"), name).isDirectory ||
            File(File(EngineConfig.engineRoot(ctx), "lib/node_modules"), name).isDirectory

    // ================= manifest 读写 =================

    fun manifestBundles(ctx: Context): List<String> {
        val file = manifestFile(ctx)
        if (!file.isFile) return emptyList()
        return runCatching { bundleNames(JSONObject(file.readText())) }.getOrElse {
            Log.w(TAG, "cannot read bundles: ${it.message}")
            emptyList()
        }
    }

    private fun bundleNames(manifest: JSONObject): List<String> {
        val arr = manifest.optJSONObject("dsh")?.optJSONObject("profile")?.optJSONArray("bundles")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
    }

    private fun writeBundles(file: File, manifest: JSONObject, names: List<String>) {
        val arr = JSONArray()
        names.forEach(arr::put)
        val dsh = manifest.optJSONObject("dsh") ?: JSONObject().also { manifest.put("dsh", it) }
        val profile = dsh.optJSONObject("profile") ?: JSONObject().also { dsh.put("profile", it) }
        profile.put("bundles", arr)
        atomicWrite(file, manifest.toString(2) + "\n")
    }

    /**
     * tmp → fsync → rename。与 [PluginRecovery] 里的写法一致：进程被杀时
     * 只会留下临时文件，正式文件要么是旧内容要么是新内容。
     */
    private fun atomicWrite(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp-${System.nanoTime()}")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(StandardCharsets.UTF_8))
            out.flush()
            // fsync 失败（部分 FUSE 挂载）不影响 rename 的原子性，只影响断电持久性
            runCatching { out.fd.sync() }
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("rename failed for ${file.name}")
        }
    }
}
