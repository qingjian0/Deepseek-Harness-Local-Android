package dsh.dsh.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import dsh.dsh.mobile.engine.EngineConfig
import dsh.dsh.mobile.engine.RepoConfig
import dsh.dsh.mobile.engine.RuntimeDownloadConfig
import dsh.dsh.mobile.engine.RuntimeIndexClient
import dsh.dsh.mobile.engine.RuntimeInstaller
import org.json.JSONObject
import java.io.File

/**
 * 关于页（MIUI 风格）：应用标识卡 + 介绍/特性 + 开源地址（点击复制）。
 * v1.4.18：与设置页一致，跟随应用屏幕朝向偏好（不再固定竖屏）。
 */
class AboutActivity : ThemedActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyTo(this)
        setContentView(R.layout.activity_about)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.txtVersion).text =
            getString(R.string.about_version, versionName())

        // 开源地址：点击复制到剪贴板（WebView 只放行回环，外链交系统浏览器亦可，复制更轻）
        findViewById<android.view.View>(R.id.rowRepo).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("dsh-android", getString(R.string.about_repo)))
            Toast.makeText(this, getString(R.string.about_copied), Toast.LENGTH_SHORT).show()
        }

        // QQ 交流群（v1.4.0）：点击复制群号 —— 与开源地址同样的轻交互。
        // 不尝试 deep link 拉起 QQ：QQ 的加群链接格式各版本不一，且未安装时会失败；
        // 复制群号让用户自己搜索是唯一在所有设备上都成立的路径。
        findViewById<android.view.View>(R.id.rowQqGroup).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(
                ClipData.newPlainText("qq-group", getString(R.string.about_qq_group_id))
            )
            Toast.makeText(this, getString(R.string.about_qq_group_copied), Toast.LENGTH_SHORT).show()
        }

        // Telegram 交流群（v1.4.38）：与 QQ 不同，Telegram 有统一的 t.me 邀请链接，
        // 可以直接 ACTION_VIEW 打开；没装 Telegram 时系统会用浏览器兜底。
        // 链接里带邀请 token，所以放在 strings.xml 而不是写死在代码里。
        findViewById<android.view.View>(R.id.rowTelegram).setOnClickListener {
            val url = getString(R.string.about_telegram_url)
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url),
            )
            runCatching { startActivity(intent) }.onFailure {
                // 没有浏览器也没有 Telegram：复制链接，至少让用户能自己粘出去
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("telegram", url))
                Toast.makeText(this, getString(R.string.about_telegram_copied), Toast.LENGTH_SHORT).show()
            }
        }

        // 检查更新（v1.2.77）：查 GitHub Releases 是否有更高版本
        // 网络请求必须离开主线程（否则 ANR）；结果回主线程更新文案
        findViewById<android.view.View>(R.id.rowUpdate).setOnClickListener {
            // 已发现新版本 → 本次点击是"前往下载"
            val target = pendingReleaseUrl
            if (target != null) {
                openUrl(target)
                return@setOnClickListener
            }
            checkForUpdate()
        }

        // 运行时更新（v1.4.20）：与 App 更新解耦；索引必须验签。
        // v1.4.21：安装进行中忽略再次点击 —— 否则第二次点击会重新走检查，
        // 检查完又变成「可安装」，再点一次就撞进安装器（旧版直接抛
        // “runtime install already in progress”）。
        findViewById<android.view.View>(R.id.rowRuntime).setOnClickListener {
            if (runtimeInstalling || RuntimeInstaller.installing) {
                Toast.makeText(this, R.string.about_runtime_busy, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val pending = pendingRuntime
            if (pending != null) installRuntime(pending) else checkRuntime()
        }
    }

    /** 待安装的运行时（检查到更新后由再次点击触发） */
    private var pendingRuntime: RuntimeIndexClient.Entry? = null

    /** 本页是否已经拉起安装线程（防止同一页面内重复点击） */
    @Volatile
    private var runtimeInstalling = false

    private val runtimeIndexUrl: String get() = RepoConfig.RELEASES_LATEST + "/runtime-index.json"

    /** 从设置页读取下载代理；默认 gh-proxy.org，留空则直连。 */
    private fun runtimeProxy(): String = RuntimeDownloadConfig.proxy(this)

    private fun checkRuntime() {
        val tv = findViewById<TextView>(R.id.txtRuntime)
        pendingRuntime = null
        tv.text = getString(R.string.about_runtime_checking)
        tv.setTextColor(getColor(R.color.text_secondary))
        val abi = deviceAbi()
        val code = currentVersionCode()
        val proxy = runtimeProxy()
        // 本机已装运行时（版本 + 构建时间）。文件很小，在主线程读可接受；
        // 原先的 installedRuntimeVersion() 也是在这条路径上读的。
        val installed = installedRuntime()
        Thread({
            val entry = RuntimeIndexClient.resolve(
                this, runtimeIndexUrl, proxy, abi, null, code,
            )
            runOnUiThread {
                when {
                    entry == null -> {
                        tv.text = getString(
                            R.string.about_runtime_failed,
                            getString(R.string.about_runtime_no_pin),
                        )
                        tv.setTextColor(getColor(R.color.text_secondary))
                    }
                    installed != null && entry.version == installed.version -> {
                        tv.text = getString(R.string.about_runtime_latest, entry.version)
                        tv.setTextColor(getColor(R.color.success))
                    }
                    // v1.4.26 防降级：索引里的构建**不比本机新**就不提示更新。
                    // 只比版本号会漏掉「同版本号但 builtAt 更早」和「preview 频道
                    // 回退到 stable」两种情况 —— 装下去等于把用户拽回旧构建。
                    installed != null && installed.builtAt > 0 && entry.builtAt > 0 &&
                        entry.builtAt <= installed.builtAt -> {
                        tv.text = getString(R.string.about_runtime_latest, installed.version)
                        tv.setTextColor(getColor(R.color.success))
                    }
                    else -> {
                        pendingRuntime = entry
                        tv.text = getString(R.string.about_runtime_available, entry.version)
                        tv.setTextColor(getColor(R.color.accent))
                    }
                }
            }
        }, "runtime-check").apply { isDaemon = true; start() }
    }

    private fun installRuntime(entry: RuntimeIndexClient.Entry) {
        val tv = findViewById<TextView>(R.id.txtRuntime)
        tv.text = getString(R.string.about_runtime_installing, 0)
        tv.setTextColor(getColor(R.color.accent))
        pendingRuntime = null
        runtimeInstalling = true
        val proxy = runtimeProxy()
        Thread({
            // 运行中的引擎会持续读 engine/lib；先停引擎再替换运行时，成功后自动重启。
            val app = application as DshApp
            // ⚠️ v1.4.26：stop 失败不能装作没发生。旧实现是
            // `runCatching { stop() }` —— 引擎没停住照样往下走，换完名再删
            // engine.old，那个还在跑的进程立刻崩（读的是被删掉的旧 inode）。
            // 现在拿 stop() 的返回值当闸门：没停干净就明确报错，让用户重试。
            val stopped = runCatching { app.supervisor.stop(graceMs = 3_500) }.getOrDefault(false)
            try {
                if (!stopped) {
                    Log.w(TAG, "runtime install aborted: engine did not stop in time")
                    runOnUiThread {
                        tv.text = getString(
                            R.string.about_runtime_failed,
                            getString(R.string.about_runtime_engine_busy),
                        )
                        tv.setTextColor(getColor(R.color.danger))
                    }
                    return@Thread
                }
                RuntimeInstaller(this).installFromIndex(
                    entry = entry,
                    indexUrl = runtimeIndexUrl,
                    proxies = listOfNotNull(proxy.takeIf { it.isNotBlank() }),
                    onProgress = { frac ->
                        val pct = (frac * 100).toInt()
                        runOnUiThread {
                            tv.text = getString(R.string.about_runtime_installing, pct)
                        }
                    },
                )
                runOnUiThread {
                    tv.text = getString(R.string.about_runtime_installed)
                    tv.setTextColor(getColor(R.color.success))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tv.text = getString(
                        R.string.about_runtime_failed,
                        e.message ?: e.javaClass.simpleName,
                    )
                    tv.setTextColor(getColor(R.color.danger))
                }
            } finally {
                runtimeInstalling = false
                runCatching { app.supervisor.restart() }
            }
        }, "runtime-install").apply { isDaemon = true; start() }
    }

    private fun deviceAbi(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "arm64-v8a"
        "x86_64" -> "x86_64"
        else -> Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
    }

    private fun currentVersionCode(): Int = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt() else info.versionCode
    }.getOrDefault(0)

    /** 本机已装运行时：版本号 + 构建时间（builtAt 用于防降级判断）。 */
    private data class InstalledRuntime(val version: String, val builtAt: Long)

    private fun installedRuntime(): InstalledRuntime? = runCatching {
        val root = EngineConfig.engineRoot(this)
        val meta = File(root, ".runtime.json")
        if (meta.isFile) {
            val o = JSONObject(meta.readText())
            InstalledRuntime(
                version = o.optString("version"),
                builtAt = o.optLong("builtAt", 0L),
            )
        } else {
            File(root, ".runtime-version").takeIf { it.isFile }
                ?.readText()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { InstalledRuntime(it, 0L) }
        }
    }.getOrNull()

    /** 待跳转的 Release 地址（检测到新版本后由点击触发跳转） */
    private var pendingReleaseUrl: String? = null

    /** 检测更新：后台请求 → 主线程回显 */
    private fun checkForUpdate() {
        val tv = findViewById<TextView>(R.id.txtUpdate)
        tv.text = getString(R.string.about_update_checking)
        val current = versionName()
        Thread({
            val r = dsh.dsh.mobile.engine.UpdateChecker.check(this, current)
            runOnUiThread {
                when {
                    r.hasUpdate -> {
                        pendingReleaseUrl = r.releaseUrl
                        tv.text = getString(R.string.about_update_available, r.latestTag)
                        tv.setTextColor(dsh.dsh.mobile.Accent.current(applicationContext).argb)
                    }
                    r.error.isNotEmpty() -> {
                        tv.text = getString(R.string.about_update_failed, r.error)
                        tv.setTextColor(getColor(R.color.text_secondary))
                    }
                    else -> {
                        tv.text = getString(R.string.about_update_latest)
                        tv.setTextColor(getColor(R.color.success))
                    }
                }
            }
        }, "update-check").apply { isDaemon = true; start() }
    }

    /** 用系统浏览器打开外链（本 App 的 WebView 只放行回环地址，外链必须交系统） */
    private fun openUrl(url: String) {
        runCatching {
            startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Toast.makeText(this, getString(R.string.about_update_failed, it.message.orEmpty()), Toast.LENGTH_LONG).show()
        }
    }

    /** 从包管理器读取 versionName（与设置页同源） */
    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "?"

    private companion object {
        private const val TAG = "About"
    }
}
