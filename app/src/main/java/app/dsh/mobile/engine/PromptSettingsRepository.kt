package app.dsh.mobile.engine

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Android-owned overrides for the upstream system-prompt and instruction-loader rows. */
object PromptSettingsRepository {
    data class Settings(
        val includeEnvironmentGuide: Boolean = true,
        val includeHarnessIdentity: Boolean = true,
        val includeRuntimeContext: Boolean = true,
        val personaPrefix: String = "",
        val personaSuffix: String = "",
        val sessionPrompt: String = "",
        val toolPrompt: String = "",
        val maxBytes: Int = 65536,
        val maxSourceBytes: Int = 1048576,
    )

    private const val PREFS = "prompt_manager_settings"
    private val lock = Any()

    fun load(context: Context): Settings {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            includeEnvironmentGuide = p.getBoolean("include_environment_guide", true),
            includeHarnessIdentity = p.getBoolean("include_harness_identity", true),
            includeRuntimeContext = p.getBoolean("include_runtime_context", true),
            personaPrefix = p.getString("persona_prefix", "").orEmpty(),
            personaSuffix = p.getString("persona_suffix", "").orEmpty(),
            sessionPrompt = p.getString("session_prompt", "").orEmpty(),
            toolPrompt = p.getString("tool_prompt", "").orEmpty(),
            maxBytes = p.getInt("max_bytes", 65536),
            maxSourceBytes = p.getInt("max_source_bytes", 1048576),
        )
    }

    fun save(context: Context, settings: Settings): Boolean = synchronized(lock) {
        validate(settings)
        writePrefs(context, settings)
    }

    private fun validate(settings: Settings) {
        require(settings.maxBytes in 1024..262144) {
            "Instruction budget must be 1024..262144 bytes"
        }
        require(settings.maxSourceBytes in 4096..4194304) {
            "Per-file limit must be 4096..4194304 bytes"
        }
        listOf(settings.personaPrefix, settings.personaSuffix, settings.sessionPrompt, settings.toolPrompt).forEach { value ->
            require(value.none { it == '\u0000' }) { "Prompt text contains a NUL character" }
            require(value.toByteArray(Charsets.UTF_8).size <= 131072) {
                "Each custom prompt section must be at most 131072 UTF-8 bytes"
            }
        }
        listOf(settings.personaPrefix, settings.personaSuffix).forEach { value ->
            require(!value.contains("{{")) {
                "Persona text cannot contain unresolved {{ variable groups"
            }
        }
    }

    private fun writePrefs(context: Context, settings: Settings): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("include_environment_guide", settings.includeEnvironmentGuide)
            .putBoolean("include_harness_identity", settings.includeHarnessIdentity)
            .putBoolean("include_runtime_context", settings.includeRuntimeContext)
            .putString("persona_prefix", settings.personaPrefix)
            .putString("persona_suffix", settings.personaSuffix)
            .putString("session_prompt", settings.sessionPrompt)
            .putString("tool_prompt", settings.toolPrompt)
            .putInt("max_bytes", settings.maxBytes)
            .putInt("max_source_bytes", settings.maxSourceBytes)
            .commit()

    fun backupPayload(context: Context): ByteArray {
        val value = load(context)
        return JSONObject()
            .put("includeEnvironmentGuide", value.includeEnvironmentGuide)
            .put("includeHarnessIdentity", value.includeHarnessIdentity)
            .put("includeRuntimeContext", value.includeRuntimeContext)
            .put("personaPrefix", value.personaPrefix)
            .put("personaSuffix", value.personaSuffix)
            .put("sessionPrompt", value.sessionPrompt)
            .put("toolPrompt", value.toolPrompt)
            .put("maxBytes", value.maxBytes)
            .put("maxSourceBytes", value.maxSourceBytes)
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun parseBackupPayload(payload: ByteArray): Settings? = runCatching {
        val json = JSONObject(String(payload, Charsets.UTF_8))
        Settings(
            includeEnvironmentGuide = json.getBoolean("includeEnvironmentGuide"),
            includeHarnessIdentity = json.getBoolean("includeHarnessIdentity"),
            includeRuntimeContext = json.getBoolean("includeRuntimeContext"),
            personaPrefix = json.getString("personaPrefix"),
            personaSuffix = json.getString("personaSuffix"),
            sessionPrompt = json.getString("sessionPrompt"),
            toolPrompt = json.getString("toolPrompt"),
            maxBytes = json.getInt("maxBytes"),
            maxSourceBytes = json.getInt("maxSourceBytes"),
        ).also(::validate)
    }.getOrNull()

    fun isValidBackupPayload(payload: ByteArray): Boolean = parseBackupPayload(payload) != null

    fun restoreBackup(context: Context, payload: ByteArray): Boolean = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hasLocalSettings = listOf(
            "include_environment_guide", "include_harness_identity", "include_runtime_context",
            "persona_prefix", "persona_suffix", "session_prompt", "tool_prompt",
            "max_bytes", "max_source_bytes",
        ).any { prefs.contains(it) }
        if (hasLocalSettings) return false
        val settings = parseBackupPayload(payload) ?: return false
        writePrefs(context, settings)
    }


    /** JSON string literals are valid YAML double-quoted scalars. */
    fun yamlString(value: String): String = JSONObject.quote(value)

    /** Mirror the session/tool guidance into the DSH home for the local prompt plugin. */
    fun syncGuidanceFile(context: Context, settings: Settings = load(context)) {
        val target = File(EngineConfig.dshHome(context), "settings/prompt-guidance.json")
        target.parentFile?.mkdirs()
        val json = JSONObject()
            .put("sessionPrompt", settings.sessionPrompt)
            .put("toolPrompt", settings.toolPrompt)
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }
}
