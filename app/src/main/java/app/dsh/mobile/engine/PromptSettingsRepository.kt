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
        require(settings.maxBytes in 1024..262144) {
            "Instruction budget must be 1024..262144 bytes"
        }
        require(settings.maxSourceBytes in 4096..4194304) {
            "Per-file limit must be 4096..4194304 bytes"
        }
        listOf(settings.personaPrefix, settings.personaSuffix, settings.sessionPrompt, settings.toolPrompt).forEach { value ->
            require(value.none { it == '\u0000' }) { "Prompt text contains a NUL character" }
            require(!value.contains("{{")) {
                "Persona text cannot contain unresolved {{ variable groups"
            }
        }
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
