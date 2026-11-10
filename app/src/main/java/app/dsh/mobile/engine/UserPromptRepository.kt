package app.dsh.mobile.engine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.IOException

/** Single source of truth for user instructions. AGENTS.md is a rendered artifact only. */
object UserPromptRepository {
    private const val PREFS_NAME = "dsh_user_prompt"
    private const val KEY_CONTENT = "user_prompt_content"
    private const val KEY_ENABLED = "user_prompt_enabled"
    private const val KEY_MIGRATED = "user_prompt_migrated"
    private const val BACKUP_SCHEMA = 1
    private const val MAX_BACKUP_BYTES = 256 * 1024
    private val migrationLock = Any()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun content(context: Context): String = prefs(context).getString(KEY_CONTENT, "") ?: ""
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)
    fun isMigrated(context: Context): Boolean = prefs(context).getBoolean(KEY_MIGRATED, false)

    fun saveContent(context: Context, value: String): Boolean =
        prefs(context).edit().putString(KEY_CONTENT, value).putBoolean(KEY_MIGRATED, true).commit()

    fun setEnabled(context: Context, enabled: Boolean): Boolean =
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit()

    /** Must run before AgentContextSeed.ensure() can normalize or rewrite the old file. */
    fun migrateLegacyIfNeeded(context: Context) {
        synchronized(migrationLock) {
            val p = prefs(context)
            if (p.getBoolean(KEY_MIGRATED, false)) return
            val file = java.io.File(EngineConfig.dshHome(context), "AGENTS.md")
            val legacy = if (file.isFile) file.readText(Charsets.UTF_8) else null
            val extracted = UserPromptSectionRenderer.extractLegacyContent(legacy)
            val edit = p.edit().putBoolean(KEY_MIGRATED, true)
            if (!extracted.isNullOrEmpty()) edit.putString(KEY_CONTENT, extracted)
            if (!edit.commit()) throw IOException("cannot persist legacy user prompt migration")
        }
    }

    fun backupPayload(context: Context): ByteArray =
        JSONObject().put("schemaVersion", BACKUP_SCHEMA)
            .put("content", content(context)).put("enabled", isEnabled(context))
            .toString().toByteArray(Charsets.UTF_8)

    fun isValidBackupPayload(bytes: ByteArray): Boolean {
        if (bytes.size > MAX_BACKUP_BYTES) return false
        return runCatching {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            json.optInt("schemaVersion", -1) == BACKUP_SCHEMA &&
                json.opt("content") is String && json.opt("enabled") is Boolean
        }.getOrDefault(false)
    }

    /** Merge restore: never replace explicit local content or enablement. */
    fun restoreBackup(context: Context, bytes: ByteArray): Boolean {
        if (!isValidBackupPayload(bytes)) throw IOException("invalid user prompt backup")
        synchronized(migrationLock) {
            val p = prefs(context)
            if (p.contains(KEY_CONTENT) || p.contains(KEY_ENABLED)) return false
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val edit = p.edit().putString(KEY_CONTENT, json.getString("content"))
                .putBoolean(KEY_ENABLED, json.getBoolean("enabled"))
                .putBoolean(KEY_MIGRATED, true)
            if (!edit.commit()) throw IOException("cannot restore user prompt settings")
            return true
        }
    }
}
