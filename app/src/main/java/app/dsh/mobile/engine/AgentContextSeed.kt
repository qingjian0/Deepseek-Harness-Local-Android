package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 仅保留用户指令管理；不再在启动时写入默认 Android 环境提示。
 *
 * 旧实现把整份环境说明写入 `$DSH_HOME/AGENTS.md`，这会注入到所有会话。
 * 现版本只保留用户自定义指令的读取、写入和安全兼容逻辑，避免覆盖/丢失用户内容。
 */
object AgentContextSeed {

    private const val TAG = "AgentContextSeed"
    private const val FILE_NAME = "AGENTS.md"
    private const val LEGACY_APP_MARKER_PREFIX = "<!-- dsh-android AGENTS seed v"
    private const val USER_SECTION_BEGIN = "<!-- >>> user instructions (app never edits below) >>> -->"
    private const val USER_SECTION_END = "<!-- <<< user instructions <<< -->"
    private const val USER_SECTION_PLACEHOLDER =
        "<!-- Add your own instructions below this line. DSH Mobile never overwrites\n" +
            "     this section, and upgrades keep it as-is. Ask the AI to write here for you. -->"

    /** 读取用户段；无用户内容时返回 null。 */
    fun readUserSection(ctx: Context): String? {
        val file = File(EngineConfig.dshHome(ctx), FILE_NAME)
        val text = runCatching { if (file.isFile) file.readText() else null }.getOrNull() ?: return null
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return null

        val user = parseUserSection(text)
        if (user != null) return user

        if (!text.contains(LEGACY_APP_MARKER_PREFIX)) {
            return cleaned
        }

        return null
    }

    /**
     * 保存用户段。
     *
     * - 已有分段格式：只更新用户段。
     * - 旧版有明确 App seed 标记但无用户段：安全清理 App 段，保留空用户段。
     * - 无法可靠区分时：不尝试破坏性改写原文件。
     */
    fun writeUserSection(ctx: Context, content: String?) {
        val file = File(EngineConfig.dshHome(ctx), FILE_NAME)
        val existing = runCatching { if (file.isFile) file.readText() else null }.getOrNull()
        val value = content?.trim()?.ifBlank { null }

        if (existing == null) {
            write(file, if (value == null) "" else userSection(value))
            return
        }

        val parsedUser = parseUserSection(existing)
        if (parsedUser != null || !existing.contains(LEGACY_APP_MARKER_PREFIX)) {
            val next = if (value == null) userSection(null) else userSection(value)
            write(file, next)
            return
        }

        // 旧版 seed：清理 App 段并保留/创建用户段；没有用户内容则保留空用户段。
        val next = userSection(value)
        write(file, next)
        Log.i(TAG, "legacy AGENTS.md stripped to user section")
    }

    private fun parseUserSection(text: String): String? {
        val begin = text.indexOf(USER_SECTION_BEGIN)
        if (begin < 0) return null
        val bodyStart = text.indexOf('\n', begin).let { if (it < 0) text.length else it + 1 }
        val end = text.indexOf(USER_SECTION_END, bodyStart)
        val body = if (end < 0) text.substring(bodyStart) else text.substring(bodyStart, end)
        val cleaned = body.replace(USER_SECTION_PLACEHOLDER, "").trim()
        return cleaned.ifEmpty { null }
    }

    private fun userSection(content: String?): String = buildString {
        append("\n\n").append(USER_SECTION_BEGIN).append('\n')
        val body = content?.trim().orEmpty()
        if (body.isEmpty()) {
            append(USER_SECTION_PLACEHOLDER).append('\n')
        } else {
            append(body).append('\n')
        }
        append(USER_SECTION_END).append('\n')
    }

    private fun write(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
        }.onFailure { Log.w(TAG, "write AGENTS.md failed: ${it.message}") }
    }
}
