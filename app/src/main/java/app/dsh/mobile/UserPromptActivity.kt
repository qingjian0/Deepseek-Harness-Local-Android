package app.dsh.mobile

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.AgentContextSeed
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Unified editor for the built-in system prompt and user-owned instructions in AGENTS.md. */
class UserPromptActivity : ThemedActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private var showingSystem = true
    private var systemDraft = ""
    private var userDraft = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_user_prompt)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        val editor = findViewById<EditText>(R.id.editorPrompt)
        val hint = findViewById<TextView>(R.id.txtPath)
        val intro = findViewById<TextView>(R.id.txtPromptIntro)
        val tabs = findViewById<MaterialButtonToggleGroup>(R.id.promptTypeTabs)
        val delete = findViewById<android.view.View>(R.id.btnDeletePrompt)

        systemDraft = AgentContextSeed.readSystemPrompt(this).orEmpty()
        userDraft = AgentContextSeed.readUserSection(this).orEmpty()

        fun renderMode() {
            editor.setText(if (showingSystem) systemDraft else userDraft)
            intro.setText(if (showingSystem) R.string.prompt_system_intro else R.string.prompt_user_intro)
            hint.setText(if (showingSystem) {
                if (AgentContextSeed.isSystemPromptDeleted(this)) R.string.prompt_system_deleted_path
                else R.string.prompt_system_path
            } else R.string.prompt_user_path)
            delete.visibility = if (showingSystem) android.view.View.VISIBLE else android.view.View.GONE
        }

        tabs.check(R.id.btnSystemPrompt)
        tabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (showingSystem) systemDraft = editor.text.toString() else userDraft = editor.text.toString()
            showingSystem = checkedId == R.id.btnSystemPrompt
            renderMode()
        }
        renderMode()

        findViewById<android.view.View>(R.id.btnSave).setOnClickListener {
            val text = editor.text.toString()
            if (showingSystem) {
                systemDraft = text
                if (text.isBlank()) AgentContextSeed.deleteSystemPrompt(this)
                else AgentContextSeed.writeSystemPrompt(this, text)
                Toast.makeText(this, R.string.prompt_system_saved, Toast.LENGTH_SHORT).show()
            } else {
                userDraft = text
                AgentContextSeed.writeUserSection(this, text.ifBlank { null })
                Toast.makeText(this, R.string.user_prompt_saved, Toast.LENGTH_SHORT).show()
            }
            finish()
        }

        delete.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.prompt_delete_system_title)
                .setMessage(R.string.prompt_delete_system_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.prompt_delete_system_confirm) { _, _ ->
                    AgentContextSeed.deleteSystemPrompt(this)
                    systemDraft = ""
                    if (showingSystem) editor.setText("")
                    renderMode()
                    Toast.makeText(this, R.string.prompt_system_deleted, Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        OrientationPrefs.applyToSettingsPage(this)
    }
}
