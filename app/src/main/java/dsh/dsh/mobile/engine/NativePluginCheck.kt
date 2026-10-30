package dsh.dsh.mobile.engine

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 插件安装前的原生模块预检（v1.4.15）。
 *
 * ## 为什么需要
 *
 * 用户反馈两类问题，根因是同一个：插件中心直接调 `dsh plugin add`，对「这个插件能不能
 * 在这台 Android 上真正跑起来」一无所知。
 *
 *   · `ERR_PNPM_IGNORED_BUILDS`：pnpm 12 默认**拒绝执行**依赖的 build 脚本
 *     （安全策略）。带原生模块的插件装完了但二进制没编译，`build/` 目录根本不存在，
 *     用户拿到一个装成功却用不了的插件。
 *   · 装完直接起不来：原生模块加载失败可能把整个 Cordis 树带崩。
 *
 * ## 检测什么
 *
 * 从 registry 的 version manifest 读四个信号：
 *
 * | 信号 | 含义 |
 * |---|---|
 * | `scripts.install` / `postinstall` / `preinstall` | 经典原生编译入口 |
 * | `gypfile: true` | node-gyp 项目 |
 * | 发布包里有 `binding.gyp`（由 `files` 字段推断） | 同上 |
 * | `os` / `cpu` 字段不含 `android` | 上游明确声明不支持的平台 |
 *
 * ## 为什么不直接检查「有没有 prebuilds」
 *
 * `better-sqlite3` 实测：`prebuilds/` 里有 linuxmusl-x64 / win32-arm64 / win32-x64，
 * **唯独没有 android**。而它 `scripts` 里也没有 install 钩子（用 prebuild-install 的
 * 变体）。只看 scripts 会漏判，只看 prebuilds 需要下载整个 tarball。所以这里用
 * 「宁可疑」的策略：**只要不是纯 JS，就提示用户风险**，让用户自己决定。
 *
 * 检测是**只读的网络查询**，不改本地任何东西；网络失败时返回「未知」而不是拦住用户。
 */
object NativePluginCheck {

    private const val TAG = "NativePluginCheck"

    /** 预检结果 */
    sealed interface Result {
        /** 纯 JS 插件，可以直接装 */
        data object PureJs : Result

        /** 需要原生编译 / 目标平台不匹配，附带给用户看的原因 */
        data class Risk(val reasons: List<String>) : Result

        /** 查不到（离线 / 非 registry 源如 git、本地路径），按未知处理 */
        data class Unknown(val reason: String) : Result

        /**
         * 插件要求的引擎版本高于内置版本（issue #66）。
         *
         * 单独成态而不是塞进 [Risk]：Risk 的语义是「插件自身有问题」，
         * 而这个是「我们的引擎旧了」——给用户的预期完全不同（等升级 App，
         * 而不是换插件）。
         */
        data class EngineTooOld(val required: String, val bundled: String) : Result
    }

    /**
     * 查一个 npm 包的预检结果。**阻塞调用**，调用方放后台线程。
     *
     * @param pkg 安装 spec（`name`、`name@1.2.3`、`name@^1`）
     * @return 预检结果；非 registry spec（git/本地/URL）返回 [Result.Unknown]
     */
    fun check(pkg: String): Result {
        val name = registryName(pkg) ?: return Result.Unknown("not a registry package")
        val base = System.getenv("DSH_NPM_REGISTRY")?.trimEnd('/')
            ?: "https://registry.npmmirror.com"
        val json = fetchJson("$base/$name/latest")
            ?: return Result.Unknown("registry unreachable")
        return analyse(name, json)
    }

    /**
     * 从 manifest 判定。抽成独立函数便于单测（不依赖网络）。
     */
    /**
     * 从 `>=0.2.1-rc.3` / `^0.2.1` / `0.2.1-rc.4` 这类范围表达式里取出**下限**。
     *
     * 只处理常见前缀（`>=`、`>`、`^`、`~`、裸版本）。复杂范围（`||`、区间）
     * 返回 null —— 宁可漏判也不误判：误判会把能用的插件标成不可用。
     */
    internal fun minVersionOf(range: String): String? {
        val first = range.split("||").firstOrNull()?.trim().orEmpty()
        if (first.isEmpty()) return null
        val token = first.split(" ").firstOrNull()?.trim().orEmpty()
        val stripped = token.trimStart('>', '=', '^', '~', ' ').trim()
        // 用原始字符串（Kotlin """…"""）避免 \d 被当转义序列
        val versionLike = Regex("""^\d+\.\d+\.\d+""")
        return if (versionLike.containsMatchIn(stripped)) stripped else null
    }

    /**
     * 语义化版本比较，**按 semver 的预发布规则**：
     * `0.2.1-rc.2 < 0.2.1-rc.4 < 0.2.1`（预发布版小于同号正式版）。
     *
     * 朴素的字符串比较在这里是错的：`"rc.10" < "rc.9"` 按字典序成立，
     * 但按版本语义应该是反的。所以预发布段要按点切分、数字段按数值比。
     *
     * @return 负数 = a 比 b 旧；0 = 相同；正数 = a 比 b 新
     */
    internal fun compareSemver(a: String, b: String): Int {
        fun split(v: String): Pair<List<Int>, List<String>> {
            val core = v.substringBefore('-')
            val pre = v.substringAfter('-', "")
            val nums = core.split('.').map { it.toIntOrNull() ?: 0 }
            val pres = if (pre.isEmpty()) emptyList() else pre.split('.')
            return nums to pres
        }
        val (an, ap) = split(a)
        val (bn, bp) = split(b)
        for (i in 0 until maxOf(an.size, bn.size)) {
            val x = an.getOrElse(i) { 0 }
            val y = bn.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        // 主版本号相同：无预发布 > 有预发布（1.0.0 > 1.0.0-rc.1）
        if (ap.isEmpty() && bp.isEmpty()) return 0
        if (ap.isEmpty()) return 1
        if (bp.isEmpty()) return -1
        for (i in 0 until maxOf(ap.size, bp.size)) {
            val x = ap.getOrNull(i) ?: return -1
            val y = bp.getOrNull(i) ?: return 1
            val xn = x.toIntOrNull(); val yn = y.toIntOrNull()
            val cmp = if (xn != null && yn != null) xn - yn else x.compareTo(y)
            if (cmp != 0) return cmp
        }
        return 0
    }

    /**
     * 内置引擎版本（如 `0.2.1-rc.2`）；读不到返回 null（**不因此误判不兼容**）。
     *
     * 从 engine root 下 `@deepseek-ai/dsh/package.json` 读，与 `DataExport`
     * 和 `SelfCheck` 用的是同一份事实来源 —— App 进程能直接读（同 uid）。
     */
    internal fun bundledEngineVersion(): String? = runCatching {
        val ctx = appContextRef ?: return@runCatching null
        val pkg = java.io.File(
            dsh.dsh.mobile.engine.EngineConfig.engineRoot(ctx),
            "lib/node_modules/@deepseek-ai/dsh/package.json",
        )
        if (!pkg.isFile) return@runCatching null
        JSONObject(pkg.readText()).getString("version")
    }.getOrNull()

    /**
     * App Context 弱引用。`NativePluginCheck` 是纯静态 object（没有 Context 参数），
     * 而引擎版本必须从应用私有目录读。由 `DshApp.onCreate` 注入一次。
     * 用 applicationContext 不会泄漏 Activity。
     */
    @Volatile
    private var appContextRef: android.content.Context? = null

    fun attachContext(ctx: android.content.Context) {
        appContextRef = ctx.applicationContext
    }

    internal fun analyse(name: String, manifest: JSONObject): Result {
        val reasons = mutableListOf<String>()

        // 1) 显式声明的平台限制：上游写明了不支持 android / linux
        val osList = stringList(manifest.opt("os"))
        if (osList.isNotEmpty() && osList.none { it == "android" || it == "linux" }) {
            reasons += "declares os=${osList.joinToString(",")}"
        }

        // 1b) engines.dsh：插件声明的引擎版本下限高于内置引擎 → 装上也跑不起来。
        // issue #66：npm 上 dsh 的 `latest` 停在 0.2.1-rc.2，而 `next` 已到 rc.4；
        // 插件作者照 next 线写 engines，我们按 latest 内置 —— 生态错位。
        // 这里只做「装之前能不能知道」这一层，真正的解法是升级内置引擎。
        val engines = manifest.optJSONObject("engines")
        val needRaw = engines?.optString("dsh", "").orEmpty().trim()
        if (needRaw.isNotEmpty()) {
            val need = minVersionOf(needRaw)
            val have = bundledEngineVersion()
            if (need != null && have != null && compareSemver(need, have) > 0) {
                // 直接返回：引擎不够新时，插件自身再好也用不了，不必再分析别的
                return Result.EngineTooOld(need, have)
            }
        }

        // 2) 经典 build 钩子
        val scripts = manifest.optJSONObject("scripts")
        if (scripts != null) {
            for (hook in listOf("preinstall", "install", "postinstall")) {
                val cmd = scripts.optString(hook, "")
                if (cmd.isBlank()) continue
                // 只认「真的在编译 / 装二进制」的命令；纯 echo、纯 js 脚本不算
                if (looksLikeNativeBuild(cmd)) {
                    reasons += "scripts.$hook: ${cmd.take(60)}"
                }
            }
        }

        // 3) node-gyp 项目
        if (manifest.optBoolean("gypfile", false)) reasons += "gypfile: true"
        if (manifest.optJSONObject("binary") != null) reasons += "binary field (prebuilt download)"

        // 4) files 里带 binding.gyp / prebuilds —— 即便没有 gypfile 标记
        val files = stringList(manifest.opt("files"))
        if (files.any { it.contains("binding.gyp") }) reasons += "ships binding.gyp"
        if (files.any { it.startsWith("prebuilds") }) reasons += "ships prebuilds/ (platform must match)"

        // 5) 依赖里出现原生相关的传递依赖
        val deps = manifest.optJSONObject("dependencies")
        if (deps != null) {
            for (k in deps.keys().asSequence()) {
                if (k in NATIVE_HINT_DEPS) reasons += "depends on $k"
            }
        }

        // 6) 平台专用 optionalDependencies（v1.4.15，sharp 是典型）
        //
        // sharp 的 manifest 里**没有任何** install 脚本、没有 gypfile、files 也是空的，
        // 唯一的信号是 optionalDependencies 列了一堆平台变体：
        //   @img/sharp-linux-arm64 / @img/sharp-win32-x64 / @img/sharp-darwin-arm64 ...
        // 里面**没有 android**。这种包在 Android 上装得到但加载不了原生库。
        // 判据：optionalDeps 里出现 ≥2 个平台变体，且没有一个带 android。
        val optional = manifest.optJSONObject("optionalDependencies")
        if (optional != null) {
            val names = optional.keys().asSequence().toList()
            val platformish = names.filter { n ->
                PLATFORM_TOKENS.any { n.contains(it, ignoreCase = true) }
            }
            if (platformish.size >= 2 && platformish.none { it.contains("android", true) }) {
                reasons += "optionalDependencies list ${platformish.size} platform " +
                    "variants, none for android"
            }
        }

        // 7) 整栈 fork 插件（v1.4.17，用户反馈「改系统提示词 + 提供 skill 的插件完全不可用」）
        //
        // 生态里有一类「换芯」插件（@knyazevai/dsh-system-prompt、
        // @monotykamary/dsh-system-prompt 等）：导出官方同名 Service 的 fork 实现，
        // 但 imports 与 peerDependencies 全部指向**作者自己 fork 的引擎核心**
        // （cordis / dsh-llm / dsh-scope / dsh-invariants 的非官方作用域版本）。
        // 它们是为作者的 fork 引擎写的，装在官方引擎上三层全断：
        //   ① `dsh` 字段为空（无 bundle patch 声明）→ `dsh plugin add` 装上后
        //      没有任何 entry 挂载，纯装等于没装；
        //   ② 用户手工在 profile patch 里换芯会被 applyEntryPatches 的 name
        //      匹配守卫**静默跳过**（name 与 base 行不一致，v1.4.7 踩过的同一机制）；
        //   ③ fork 的 peerDependencies 在 autoInstallPeers=false 下不会自动安装，
        //      即便装上，fork cordis 与官方 cordis 同进程共存也无法工作。
        // 判据：peerDependencies 里出现「非 @deepseek-ai 作用域」的引擎核心组件。
        val peers = manifest.optJSONObject("peerDependencies")
        if (peers != null) {
            val forks = peers.keys().asSequence().filter { dep ->
                val scope = if (dep.startsWith("@")) dep.substringBefore('/') else ""
                scope != "@deepseek-ai" && dep.substringAfter('/').substringBefore('@') in
                    FORK_ENGINE_COMPONENTS
            }.toList()
            if (forks.isNotEmpty()) {
                reasons += "engine fork dependency: ${forks.joinToString(", ")}"
            }
        }

        return if (reasons.isEmpty()) Result.PureJs else Result.Risk(reasons.distinct())
    }

    /**
     * 引擎核心组件的「裸名」（去掉作用域后的部分）。peerDependencies 里这些组件
     * 的作用域不是 @deepseek-ai，即判定为 fork 引擎插件。
     */
    private val FORK_ENGINE_COMPONENTS = setOf(
        "cordis", "dsh-llm", "dsh-scope", "dsh-invariants",
        "dsh-system-prompt", "dsh-schemastery", "schemastery",
    )

    /** 包名里的平台标记（用于识别平台专用 optionalDependencies） */
    private val PLATFORM_TOKENS = listOf(
        "-linux", "-darwin", "-win32", "-freebsd", "-android", "-musl",
    )

    /** 判断一条 install 脚本是不是在装/编译二进制 */
    private fun looksLikeNativeBuild(cmd: String): Boolean {
        val low = cmd.lowercase()
        // 明确的原生构建器
        if (NATIVE_BUILD_TOOLS.any { low.contains(it) }) return true
        // node-gyp 相关关键字
        if (low.contains("node-gyp") || low.contains("node-pre-gyp")) return true
        if (low.contains("prebuild-install") || low.contains("cmake-js")) return true
        // 纯 JS 的 install 脚本（比如生成一个文件）不算
        return false
    }

    private val NATIVE_BUILD_TOOLS = listOf(
        "node-gyp", "node-pre-gyp", "prebuild-install", "cmake-js",
        "node-gyp-build", "node-build", "gyp rebuild", "make ",
    )

    /** 这些依赖基本等价于「要有原生编译」 */
    private val NATIVE_HINT_DEPS = setOf(
        "node-gyp", "node-pre-gyp", "@mapbox/node-pre-gyp", "prebuild-install",
        "node-addon-api", "nan", "cmake-js", "node-gyp-build",
    )

    /**
     * 从安装 spec 取出 registry 上的包名。
     *
     * 支持 `name`、`name@1.2.3`、`@scope/name@^1`；不是 registry 包的（git URL、
     * file:、http(s) tarball、本地路径）返回 null。
     */
    internal fun registryName(spec: String): String? {
        if (spec.isBlank()) return null
        val s = spec.trim()
        if (s.startsWith("git") || s.startsWith("http") || s.startsWith("file:") ||
            s.startsWith(".") || s.startsWith("/") || s.contains("://")
        ) return null
        // @scope/name@version —— 第二个 @ 才是版本分隔符
        return if (s.startsWith("@")) {
            val slash = s.indexOf('/')
            if (slash < 0) return null
            val at = s.indexOf('@', slash)
            if (at < 0) s else s.substring(0, at)
        } else {
            val at = s.indexOf('@')
            if (at < 0) s else s.substring(0, at)
        }
    }

    private fun stringList(v: Any?): List<String> = when (v) {
        is org.json.JSONArray -> (0 until v.length()).mapNotNull {
            v.optString(it).takeIf(String::isNotBlank)
        }
        is String -> listOf(v)
        else -> emptyList()
    }

    private fun fetchJson(url: String): JSONObject? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 12_000
        conn.setRequestProperty("Accept", "application/json")
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "registry HTTP ${conn.responseCode} for $url")
                return@runCatching null
            }
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrElse {
        Log.w(TAG, "registry fetch failed: ${it.javaClass.simpleName}: ${it.message}")
        null
    }
}
