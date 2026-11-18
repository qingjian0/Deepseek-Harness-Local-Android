package app.dsh.mobile

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch
import app.dsh.mobile.engine.AgentContextSeed

/** Developer-only prompt controls. User-authored instructions remain separate from the App seed. */
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
        findViewById<View>(R.id.rowPromptUpstream).setOnClickListener {
            showInfo(R.string.prompt_upstream_title, R.string.prompt_upstream_info)
        }
        findViewById<View>(R.id.rowPromptSession).setOnClickListener {
            showInfo(R.string.prompt_session_title, R.string.prompt_session_info)
        }
        findViewById<View>(R.id.rowPromptTools).setOnClickListener {
            showInfo(R.string.prompt_tools_title, R.string.prompt_tools_info)
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

    private fun showInfo(title: Int, message: Int) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (!DeveloperModePrefs.isEnabled(this)) finish()
        OrientationPrefs.applyToSettingsPage(this)
    }
}
