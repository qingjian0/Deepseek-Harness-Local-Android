package dsh.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 插件市场（v1.4.21）—— 取代 v1.2.118 的极简 [PluginCenter.search]。
 *
 * ## 为什么重写
 *
 * 参考 DSHA 的插件中心（开源，DSH-APP/DSHA 的 PluginFragment/PluginRepository），
 * 它把「插件管理」做成了完整的应用商店形态：市场/已安装双标签、富卡片、排序、
 * 下载源切换、链接安装（npm / GitHub 仓库 / Release / 压缩包四种来源实时识别）、
 * 检查更新 / 更新 / 回退、能力声明、可取消进度、批量导出。
 *
 * 我们的加强点（DSHA 没有、Android 移植特有）：
 *  1. **兼容性分级内联标注**——复用 [NativePluginCheck] 的 7 信号分析，卡片上直接
 *     标「纯 JS / 需原生 / 引擎 fork」三态，不用点进详情才知道装不了；
 *  2. **精确版本安装/升级**——pnpm 12 的升级语义坑（详见 [install]）；
 *  3. **体积展示**——registry 的 dist.unpackedSize；
 *  4. **pnpm store 锁自愈**——沿用 [PluginCenter.healPnpmStore]。
 *
 * ## 数据来源分层（网络失败不空屏）
 *  1. npm registry 搜索（`keywords:deepseek-harness` 浏览 / 任意词搜索）；
 *  2. 我们的 community-plugins.json 精选清单（离线兜底 + 编辑推荐标记）。
 */
object PluginMarket {

    private const val TAG = "PluginMarket"

    /** 远程精选清单（我们维护；raw 直读无需鉴权） */
    private const val CATALOG_URL =
        "https://raw.githubusercontent.com/Soodok/Deepseek-Harness-Local-Android/main/community-plugins.json"

    private const val REGISTRY_SEARCH = "https://registry.npmjs.org/-/v1/search"

    // ─────────────────────────────────────────────────────────────────────
    // 数据模型
    // ─────────────────────────────────────────────────────────────────────

    /** 兼容性三态（来自 NativePluginCheck 的 7 信号分析，或本地目录未验证） */
    enum class Compat {
        /** 纯 JS，Android 可直接运行 */
        PURE_JS,

        /** 需要原生编译 / 平台不匹配（装了多半不能用） */
        NEEDS_NATIVE,

        /** 整栈引擎 fork（破甲类插件）——官方引擎上设计性不兼容 */
        ENGINE_FORK,

        /** 查不到（离线 / git 源 / 未请求） */
        UNKNOWN,
    }

    /**
     * 市场条目（同时用于「市场」与「已安装」两个标签）。
     *
     * @param pkg npm 包名（`@scope/name`）
     * @param version registry 上的最新版本（市场标签）或已安装版本（已安装标签）
     * @param installedVer 实际落盘版本（已安装标签；null = 未安装）
     * @param unpackedSize 解压后字节数（0 = 未知）
     * @param official 我们清单里的编辑推荐
     * @param compat 兼容性判定；[compatReasons] 是对用户可见的判断依据
     */
    data class Entry(
        val pkg: String,
        val name: String,
        val desc: String = "",
        val version: String = "",
        val installedVer: String? = null,
        val author: String = "",
        val repo: String = "",
        val unpackedSize: Long = 0L,
        val updatedAt: Long = 0L,          // registry 的 time.modified（毫秒）
        val downloadCount: Int = 0,
        val official: Boolean = false,
        val compat: Compat = Compat.UNKNOWN,
        val compatReasons: List<String> = emptyList(),
        val builtin: Boolean = false,      // 引擎自带（不可卸载）
    ) {
        val isInstalled: Boolean get() = installedVer != null
        /** npm 上有比已装版本更新的版本 */
        val hasUpdate: Boolean
            get() = isInstalled && version.isNotBlank() && installedVer != version

        /** 卡片副标题：版本 + 体积 + 作者，空字段自动省略 */
        fun metaLine(): String = buildList {
            if (installedVer != null) {
                add(if (hasUpdate) "v$installedVer → v$version" else "v$installedVer")
            } else if (version.isNotBlank()) {
                add("v$version")
            }
            if (unpackedSize > 0) add(humanSize(unpackedSize))
            if (author.isNotBlank()) add(author)
        }.joinToString(" · ")
    }

    /**
     * 排序模式（对齐 DSHA 的四种 + 我们加体积）。
     * 标签不放这里——项目 i18n 门禁禁止 Kotlin 中文字面量，UI 层按 [labelRes] 取资源。
     */
    enum class Sort(val labelRes: Int) {
        NAME_ASC(dsh.dsh.mobile.R.string.plugin_sort_name_asc),
        NAME_DESC(dsh.dsh.mobile.R.string.plugin_sort_name_desc),
        RECENT(dsh.dsh.mobile.R.string.plugin_sort_recent),
        DOWNLOADS(dsh.dsh.mobile.R.string.plugin_sort_downloads),
        SIZE(dsh.dsh.mobile.R.string.plugin_sort_size),
    }

    fun sort(entries: List<Entry>, mode: Sort): List<Entry> = when (mode) {
        Sort.NAME_ASC -> entries.sortedBy { it.name.lowercase() }
        Sort.NAME_DESC -> entries.sortedByDescending { it.name.lowercase() }
        Sort.RECENT -> entries.sortedByDescending { it.updatedAt }
        Sort.DOWNLOADS -> entries.sortedByDescending { it.downloadCount }
        Sort.SIZE -> entries.sortedBy { if (it.unpackedSize == 0L) Long.MAX_VALUE else it.unpackedSize }
    }

    /** 过滤：关键词（名称/描述/包名）+ 仅看已安装 + 隐藏内置 */
    fun filter(
        entries: List<Entry>,
        query: String,
        onlyInstalled: Boolean = false,
        hideBuiltin: Boolean = false,
    ): List<Entry> {
        val q = query.trim().lowercase()
        return entries.filter { e ->
            if (onlyInstalled && !e.isInstalled) return@filter false
            if (hideBuiltin && e.builtin) return@filter false
            q.isEmpty() ||
                e.pkg.lowercase().contains(q) ||
                e.name.lowercase().contains(q) ||
                e.desc.lowercase().contains(q)
        }
    }

    fun humanSize(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
        else -> String.format("%.2f GB", bytes / 1073741824.0)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 市场搜索
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 搜索/浏览市场。
     *
     * 空查询 = 浏览 dsh 生态（`keywords:deepseek-harness`）并标注精选；
     * 有查询词 = 搜全网 npm（用户想装什么就搜什么）。
     *
     * @param onDone 后台线程回调；(结果, 失败原因)；失败原因非空时 UI 回退精选清单
     */
    fun browse(
        query: String,
        installed: Map<String, String>,
        checkCompat: Boolean,
        onDone: (List<Entry>, String?) -> Unit,
    ) {
        Thread({
            val q = if (query.isBlank()) "keywords:deepseek-harness" else query.trim()
            val catalog = fetchCatalog()
            val officialPkgs = catalog.map { it.pkg }.toSet()
            val result = runCatching {
                val encoded = URLEncoder.encode(q, "UTF-8")
                val text = httpGet("$REGISTRY_SEARCH?text=$encoded&size=100")
                parseSearch(text, browse = query.isBlank(), officialPkgs, installed)
            }
            val remote = result.getOrElse {
                Log.w(TAG, "npm search failed: ${it.message}")
                emptyList()
            }
            // 合并策略：浏览态总是并入精选；搜索态仅在 npm 失败时兜底并入
            //（搜索有结果就尊重用户意图，不要把无关精选塞进来）。
            val merged = when {
                query.isBlank() -> mergeCatalog(remote, catalog, installed)
                remote.isEmpty() -> mergeCatalog(emptyList(), catalog, installed)
                else -> remote
            }
            val withCompat =
                if (checkCompat) merged.map { attachCompat(it) } else merged
            onDone(withCompat, result.exceptionOrNull()?.message)
        }, "dsh-market-browse").apply { isDaemon = true; start() }
    }

    /** 附加兼容性判定（只对未安装/未知的条目做网络查询；已装的不必再查） */
    private fun attachCompat(e: Entry): Entry {
        if (e.compat != Compat.UNKNOWN) return e
        return when (val r = runCatching { NativePluginCheck.check(e.pkg) }.getOrNull()) {
            is NativePluginCheck.Result.PureJs -> e.copy(compat = Compat.PURE_JS)
            is NativePluginCheck.Result.Risk -> {
                val (compat, _) = classifyRisk(r.reasons)
                e.copy(compat = compat, compatReasons = r.reasons)
            }
            is NativePluginCheck.Result.Unknown -> e.copy(
                compat = Compat.UNKNOWN,
                compatReasons = listOf(r.reason),
            )
            null -> e
        }
    }

    /**
     * Risk 细分：NativePluginCheck 的 reasons 里同时含原生模块信号与引擎 fork 信号。
     * fork 类插件（@xxx/dsh-system-prompt 等）在官方引擎上**设计性**不可用，
     * 与「原生模块编译不出来」是两种不同的坏法，分开标注才能给出正确建议。
     */
    internal fun classifyRisk(reasons: List<String>): Pair<Compat, List<String>> {
        // NativePluginCheck 的 reasons 是英文（"fork of the engine" / "engine component"）。
        // 不用中文字面量：i18n 门禁禁止，且用户语言变化不影响判定。
        val isFork = reasons.any {
            it.contains("fork", ignoreCase = true) ||
                it.contains("engine component", ignoreCase = true) ||
                it.contains("engine core", ignoreCase = true)
        }
        return (if (isFork) Compat.ENGINE_FORK else Compat.NEEDS_NATIVE) to reasons
    }

    private fun parseSearch(
        text: String,
        browse: Boolean,
        officialPkgs: Set<String>,
        installed: Map<String, String>,
    ): List<Entry> = runCatching {
        val arr = JSONObject(text).getJSONArray("objects")
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val p = o.getJSONObject("package")
            val pkg = p.getString("name")
            // 浏览态排除官方内部组件（引擎部件，不是可装插件）；显式搜索不排除
            if (browse && pkg.startsWith("@deepseek-ai/")) continue
            val links = p.optJSONObject("links")
            val publisher = p.optJSONObject("publisher")
            out.add(
                Entry(
                    pkg = pkg,
                    name = pkg.substringAfterLast('/'),
                    desc = p.optString("description", ""),
                    version = p.optString("version", ""),
                    installedVer = installed[pkg],
                    author = publisher?.optString("username", "") ?: "",
                    repo = links?.optString("repository", "") ?: "",
                    updatedAt = parseIso(p.optString("date", "")),
                    official = pkg in officialPkgs,
                    compat = Compat.UNKNOWN,
                ),
            )
        }
        out
    }.getOrDefault(emptyList())

    /** 把精选清单里没被搜索命中的条目补进来（离线可用 + 编辑推荐） */
    private fun mergeCatalog(
        remote: List<Entry>,
        catalog: List<Entry>,
        installed: Map<String, String>,
    ): List<Entry> {
        val seen = remote.map { it.pkg }.toMutableSet()
        val out = remote.toMutableList()
        for (c in catalog) {
            if (seen.add(c.pkg)) out.add(c.copy(installedVer = installed[c.pkg]))
        }
        return out
    }

    private fun fetchCatalog(): List<Entry> = runCatching {
        val text = httpGet(CATALOG_URL)
        val arr = org.json.JSONArray(text)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(
                pkg = o.getString("package"),
                name = o.optString("id", o.getString("package").substringAfterLast('/')),
                desc = o.optString("desc", ""),
                repo = o.optString("repo", ""),
                official = true,
            )
        }
    }.getOrElse {
        // 远端清单拉不到（网络重置/离线）→ 用内置精选兜底，保证市场不空屏。
        // 旧版（v1.2.113）就有这份 BUILTIN 清单，不能因为重写丢掉。
        Log.w(TAG, "catalog fetch failed (${it.message}); using bundled picks")
        PluginCenter.bundledCatalog()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 单包详情（点进详情时按需拉，避免列表阶段 100 个请求）
    // ─────────────────────────────────────────────────────────────────────

    /** registry 单包完整 manifest（含 unpackedSize / 全部版本 / maintainers） */
    fun detail(pkg: String, onDone: (Entry?, String?) -> Unit) {
        Thread({
            val r = runCatching {
                val text = httpGet("https://registry.npmjs.org/${pkg.replace("/", "%2F")}")
                val o = JSONObject(text)
                val latest = o.optJSONObject("dist-tags")?.optString("latest", "") ?: ""
                val versions = o.optJSONObject("versions")
                val vobj = versions?.optJSONObject(latest)
                val dist = vobj?.optJSONObject("dist")
                val times = o.optJSONObject("time")
                val maintainers = o.optJSONArray("maintainers")
                Entry(
                    pkg = pkg,
                    name = pkg.substringAfterLast('/'),
                    desc = o.optString("description", ""),
                    version = latest,
                    author = maintainers?.optJSONObject(0)?.optString("name", "") ?: "",
                    repo = o.optJSONObject("repository")?.optString("url", "")
                        ?: vobj?.optJSONObject("repository")?.optString("url", "") ?: "",
                    unpackedSize = runCatching {
                        o.optJSONObject("_attachments")?.let { 0L }
                        dist?.optLong("unpackedSize", 0L) ?: 0L
                    }.getOrDefault(0L),
                    updatedAt = parseIso(times?.optString(latest, "") ?: ""),
                )
            }
            if (r.isSuccess) {
                val compat = attachCompat(r.getOrThrow())
                onDone(compat, null)
            } else {
                onDone(null, r.exceptionOrNull()?.message)
            }
        }, "dsh-market-detail").apply { isDaemon = true; start() }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 已安装清单（读 profile，含实际版本）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 已安装插件清单：包名 → 实际落盘版本。同时补上 registry 最新版本以判断可升级。
     *
     * @param checkUpdates true 时逐个查 registry 的最新版本（网络；用于「检查更新」）
     */
    fun installed(
        ctx: Context,
        checkUpdates: Boolean,
        onDone: (List<Entry>, String?) -> Unit,
    ) {
        Thread({
            val versions = PluginCenter.installedVersions(ctx)
            val errs = mutableListOf<String>()
            val out = versions.map { (pkg, ver) ->
                var latest = ""
                var desc = ""
                var size = 0L
                if (checkUpdates) {
                    runCatching {
                        val text = httpGet("https://registry.npmjs.org/${pkg.replace("/", "%2F")}/latest")
                        val o = JSONObject(text)
                        latest = o.optString("version", "")
                        desc = o.optString("description", "")
                        size = o.optJSONObject("dist")?.optLong("unpackedSize", 0L) ?: 0L
                    }.onFailure { errs += "${pkg}: ${it.message}" }
                }
                Entry(
                    pkg = pkg,
                    name = pkg.substringAfterLast('/'),
                    desc = desc,
                    version = latest.ifBlank { ver },
                    installedVer = ver,
                    unpackedSize = size,
                )
            }.sortedBy { it.name.lowercase() }
            onDone(out, errs.takeIf { it.isNotEmpty() }?.joinToString("; "))
        }, "dsh-market-installed").apply { isDaemon = true; start() }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 网络
    // ─────────────────────────────────────────────────────────────────────

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "dsh-android-plugin-market")
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseIso(s: String): Long = runCatching {
        if (s.isBlank()) 0L
        else java.time.Instant.parse(s).toEpochMilli()
    }.getOrDefault(0L)
}
