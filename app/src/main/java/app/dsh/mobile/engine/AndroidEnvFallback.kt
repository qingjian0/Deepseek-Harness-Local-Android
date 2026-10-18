package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Android 环境说明书的**降级兜底**（v1.3.1 起）。
 *
 * 正路是 `packages/dsh-android-env` 插件：它以 ordered systemPrompt section 的形式
 * 每步注入正文（见该包 README 与 [EngineConfig] 生成的 overlay）。这里只做两件事：
 *
 * 1. **迁移**：老版本把 31KB 说明书 seed 成 `$DSH_HOME/AGENTS.md`。那条通道是
 *    user 角色消息，只在文件读写时刷新、会被 compaction 压掉、每会话只注入一次，
 *    所以现在归档到 `$DSH_HOME/.dsh-android/AGENTS.md.legacy` 让位给插件。
 *    **只动带旧 seed 标记的文件**；用户自己写的 AGENTS.md 一律不碰。
 * 2. **兜底**：仅当这个 build 根本没打包插件（assets 里缺失）时写一行提示，
 *    避免插件缺失时 agent 连"这里是 Android"都不知道。
 *
 * 不再管理版本号、模板与幂等升级 —— 那些是插件与 sections 目录里的 .md 的事。
 * （注意：Kotlin 块注释可嵌套，正文里不要写 `sections/` 加通配符，那个斜杠加星号
 * 会再开一层注释，导致整个文件 “Unclosed comment”——v1.3.1 CI 实测踩过。）
 * 文案必须是英文：`scripts/check-i18n.py` 禁止 Kotlin 字符串字面量里出现 CJK。
 */
object AndroidEnvFallback {

    private const val TAG = "AndroidEnvFallback"

    /** 旧 seed 的标记前缀（`AgentContextSeed` 写进文件头的那行）。 */
    private const val LEGACY_MARKER = "<!-- dsh-android AGENTS seed v"

    /** 插件入口在 assets 里的路径；存在即代表本 build 打包了插件。 */
    private const val PLUGIN_ASSET = "android-plugins/android-env/index.js"

    private const val FALLBACK_TEXT = "<!-- dsh-android fallback -->\n" +
        "This device runs DSH Mobile on Android. The android-env manual plugin is " +
        "missing from this build, so its platform facts are NOT in your prompt: do not " +
        "assume anything about libc, package managers, or the toolchain, probe as needed, " +
        "and tell the user to restart the engine or reinstall the app.\n"

    /**
     * 启动时调用一次（在引擎 fork 之前）。
     *
     * @param ctx 应用上下文。
     */
    fun ensure(ctx: Context) {
        val home = EngineConfig.dshHome(ctx)
        val agents = File(home, "AGENTS.md")
        migrateLegacy(agents, home)
        if (shipsPlugin(ctx)) return
        writeFallback(agents)
    }

    /**
     * 把老版本 seed 出来的 AGENTS.md 归档，停止 user 通道的重复注入。
     *
     * @param agents `$DSH_HOME/AGENTS.md`。
     * @param home `$DSH_HOME`。
     */
    private fun migrateLegacy(agents: File, home: File) {
        val text = runCatching { if (agents.isFile) agents.readText() else null }.getOrNull() ?: return
        if (!text.contains(LEGACY_MARKER)) {
            // 用户手写的指令：不是我们的文件，保持原样（它仍会被每会话注入一次）。
            Log.i(TAG, "AGENTS.md is user-authored (no seed marker); leaving it untouched")
            return
        }
        val archiveDir = File(home, ".dsh-android")
        val archive = File(archiveDir, "AGENTS.md.legacy")
        runCatching {
            archiveDir.mkdirs()
            // 原子发布：先写临时文件再 rename —— 中途被杀不会留下半个归档。
            val tmp = File(archiveDir, "AGENTS.md.legacy.tmp")
            tmp.writeText(text)
            if (archive.isFile && !archive.delete()) Log.w(TAG, "cannot replace old archive ${archive.absolutePath}")
            if (!tmp.renameTo(archive)) {
                Log.w(TAG, "rename to ${archive.absolutePath} failed; archive kept at ${tmp.absolutePath}")
            }
            if (!agents.delete()) Log.w(TAG, "cannot delete legacy ${agents.absolutePath}")
            Log.i(TAG, "legacy AGENTS.md archived (${text.length} bytes) -> ${archive.absolutePath}")
        }.onFailure { Log.w(TAG, "archive legacy AGENTS.md failed: ${it.message}") }
    }

    /**
     * 本 build 是否打包了 android-env 插件。
     *
     * 用 assets 而不是磁盘上的部署产物：ensure() 在引擎 fork 之前跑，
     * 那时插件文件还没部署；assets 才是"这一版有没有带插件"的稳定事实。
     *
     * @param ctx 应用上下文。
     * @returns 打包了插件返回 true。
     */
    private fun shipsPlugin(ctx: Context): Boolean = runCatching {
        ctx.assets.open(PLUGIN_ASSET).use { true }
    }.getOrDefault(false)

    /**
     * 写一行兜底提示。已有文件（哪怕内容不同）一律不覆盖。
     *
     * @param agents `$DSH_HOME/AGENTS.md`。
     */
    private fun writeFallback(agents: File) {
        if (agents.isFile) return
        runCatching {
            agents.parentFile?.mkdirs()
            agents.writeText(FALLBACK_TEXT)
            Log.w(TAG, "android-env plugin absent from this build; wrote fallback AGENTS.md")
        }.onFailure { Log.w(TAG, "write fallback AGENTS.md failed: ${it.message}") }
    }
}
