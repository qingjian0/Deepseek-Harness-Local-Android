package app.dsh.mobile

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import app.dsh.mobile.engine.PromptSettingsRepository

/** Developer-only editor for the real upstream DSH prompt configuration. */
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
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        val settings = PromptSettingsRepository.load(this)
        val environmentGuide = findViewById<MaterialSwitch>(R.id.swIncludeEnvironmentGuide).apply {
            isChecked = settings.includeEnvironmentGuide
        }
        val identity = findViewById<MaterialSwitch>(R.id.swIncludeHarnessIdentity).apply {
            isChecked = settings.includeHarnessIdentity
        }
        val runtime = findViewById<MaterialSwitch>(R.id.swIncludeRuntimeContext).apply {
            isChecked = settings.includeRuntimeContext
        }
        val prefix = findViewById<EditText>(R.id.etPersonaPrefix).apply {
            setText(settings.personaPrefix)
        }
        val suffix = findViewById<EditText>(R.id.etPersonaSuffix).apply {
            setText(settings.personaSuffix)
        }
        val sessionPrompt = findViewById<EditText>(R.id.etSessionPrompt).apply {
            setText(settings.sessionPrompt)
        }
        val toolPrompt = findViewById<EditText>(R.id.etToolPrompt).apply {
            setText(settings.toolPrompt)
        }
        val maxBytes = findViewById<EditText>(R.id.etInstructionMaxBytes).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(settings.maxBytes.toString())
        }
        val maxSourceBytes = findViewById<EditText>(R.id.etInstructionMaxSourceBytes).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(settings.maxSourceBytes.toString())
        }

        findViewById<android.view.View>(R.id.btnOpenUserPrompt).setOnClickListener {
            startActivity(android.content.Intent(this, UserPromptActivity::class.java))
        }
        findViewById<android.view.View>(R.id.btnResetPromptSettings).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.prompt_reset_title)
                .setMessage(R.string.prompt_reset_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.prompt_reset_action) { _, _ ->
                    if (PromptSettingsRepository.save(this, PromptSettingsRepository.Settings())) {
                        applyAfterSave()
                    } else {
                        Toast.makeText(this, R.string.prompt_save_failed, Toast.LENGTH_LONG).show()
                    }
                }
                .show()
        }
        findViewById<android.view.View>(R.id.btnSavePromptSettings).setOnClickListener {
            val updated = runCatching {
                PromptSettingsRepository.Settings(
                    includeEnvironmentGuide = environmentGuide.isChecked,
                    includeHarnessIdentity = identity.isChecked,
                    includeRuntimeContext = runtime.isChecked,
                    personaPrefix = prefix.text.toString(),
                    personaSuffix = suffix.text.toString(),
                    sessionPrompt = sessionPrompt.text.toString(),
                    toolPrompt = toolPrompt.text.toString(),
                    maxBytes = maxBytes.text.toString().toInt(),
                    maxSourceBytes = maxSourceBytes.text.toString().toInt(),
                )
            }.getOrElse {
                Toast.makeText(this, R.string.prompt_invalid_numbers, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            runCatching { PromptSettingsRepository.save(this, updated) }
                .onSuccess { saved ->
                    if (saved) applyAfterSave()
                    else Toast.makeText(this, R.string.prompt_save_failed, Toast.LENGTH_LONG).show()
                }
                .onFailure {
                    Toast.makeText(this, it.message ?: getString(R.string.prompt_save_failed), Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun applyAfterSave() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.prompt_saved_title)
            .setMessage(R.string.prompt_restart_message)
            .setNegativeButton(R.string.prompt_restart_later) { _, _ -> finish() }
            .setPositiveButton(R.string.prompt_restart_now) { _, _ ->
                (application as DshApp).supervisor.restart()
                finish()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (!DeveloperModePrefs.isEnabled(this)) finish()
        OrientationPrefs.applyToSettingsPage(this)
    }
}
