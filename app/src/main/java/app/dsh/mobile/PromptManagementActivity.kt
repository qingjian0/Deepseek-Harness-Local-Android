package app.dsh.mobile

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch
import app.dsh.mobile.engine.AgentContextSeed
import app.dsh.mobile.engine.PromptSettingsRepository

/** Developer-only controls for the five prompt layers. */
class PromptManagementActivity : ThemedActivity() {
    override val usesSettingsBackground: Boolean get() = true

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DeveloperModePrefs.isEnabled(this)) {
            finish()
            return
        }
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_prompt_management)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<View>(R.id.rowPromptCustom).setOnClickListener {
            startActivity(Intent(this, UserPromptActivity::class.java))
        }
        findViewById<View>(R.id.rowPromptEnvironment).setOnClickListener { showBudgetEditor() }
        findViewById<View>(R.id.rowPromptUpstream).setOnClickListener { showUpstreamEditor() }
        findViewById<View>(R.id.rowPromptSession).setOnClickListener {
            val settings = PromptSettingsRepository.load(this)
            showTextEditor(R.string.prompt_session_title, settings.sessionPrompt) {
                saveSettings(settings.copy(sessionPrompt = it))
            }
        }
        findViewById<View>(R.id.rowPromptTools).setOnClickListener {
            val settings = PromptSettingsRepository.load(this)
            showTextEditor(R.string.prompt_tools_title, settings.toolPrompt) {
                saveSettings(settings.copy(toolPrompt = it))
            }
        }

        val appSeed = findViewById<MaterialSwitch>(R.id.swPromptEnvironment)
        appSeed.isChecked = AgentContextSeed.isAppSeedEnabled(this)
        appSeed.setOnCheckedChangeListener { _, enabled ->
            AgentContextSeed.setAppSeedEnabled(this, enabled)
            Toast.makeText(
                this,
                if (enabled) R.string.prompt_environment_enabled else R.string.prompt_environment_disabled,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun showUpstreamEditor() {
        val settings = PromptSettingsRepository.load(this)
        val identity = MaterialSwitch(this).apply {
            text = getString(R.string.prompt_include_identity)
            isChecked = settings.includeHarnessIdentity
        }
        val runtime = MaterialSwitch(this).apply {
            text = getString(R.string.prompt_include_runtime_context)
            isChecked = settings.includeRuntimeContext
        }
        val prefix = promptEditText(settings.personaPrefix, R.string.prompt_persona_prefix)
        val suffix = promptEditText(settings.personaSuffix, R.string.prompt_persona_suffix)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(identity)
            addView(runtime)
            addView(label(R.string.prompt_persona_prefix))
            addView(prefix)
            addView(label(R.string.prompt_persona_suffix))
            addView(suffix)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.prompt_upstream_title)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                saveSettings(
                    settings.copy(
                        includeHarnessIdentity = identity.isChecked,
                        includeRuntimeContext = runtime.isChecked,
                        personaPrefix = prefix.text.toString(),
                        personaSuffix = suffix.text.toString(),
                    ),
                )
            }
            .show()
    }

    private fun showTextEditor(title: Int, current: String, save: (String) -> Unit) {
        val input = promptEditText(current, R.string.user_prompt_placeholder).apply {
            minLines = 5
            maxLines = 14
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(input) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> save(input.text.toString()) }
            .show()
    }

    private fun showBudgetEditor() {
        val settings = PromptSettingsRepository.load(this)
        val maxBytes = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(settings.maxBytes.toString())
            hint = "65536"
        }
        val maxSourceBytes = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(settings.maxSourceBytes.toString())
            hint = "1048576"
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(label(R.string.prompt_budget_max_bytes))
            addView(maxBytes)
            addView(label(R.string.prompt_budget_max_source_bytes))
            addView(maxSourceBytes)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.prompt_environment_title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                runCatching {
                    saveSettings(
                        settings.copy(
                            maxBytes = maxBytes.text.toString().toInt(),
                            maxSourceBytes = maxSourceBytes.text.toString().toInt(),
                        ),
                    )
                }.onFailure { showError(it.message ?: getString(R.string.prompt_environment_update_failed, "")) }
            }
            .show()
    }

    private fun promptEditText(value: String, hint: Int) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setText(value)
        setHint(hint)
        minLines = 2
        maxLines = 6
    }

    private fun label(textRes: Int) = TextView(this).apply {
        text = getString(textRes)
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun saveSettings(settings: PromptSettingsRepository.Settings) {
        runCatching { PromptSettingsRepository.save(this, settings) }
            .onSuccess { Toast.makeText(this, R.string.prompt_saved_restart, Toast.LENGTH_LONG).show() }
            .onFailure { showError(it.message ?: getString(R.string.prompt_environment_update_failed, "")) }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        if (!DeveloperModePrefs.isEnabled(this)) finish()
        OrientationPrefs.applyToSettingsPage(this)
    }
}
