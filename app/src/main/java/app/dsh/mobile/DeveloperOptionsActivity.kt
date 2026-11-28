package app.dsh.mobile

import android.content.Context
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * This page only controls menu visibility. It does not grant capabilities or skip
 * CapabilityGate authorization; each privileged operation must validate access itself.
 */
class DeveloperOptionsActivity : ThemedActivity() {
    override val usesSettingsBackground: Boolean get() = true

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Guard stale or explicit in-app launches after developer mode has been disabled.
        if (!DeveloperModePrefs.isEnabled(this)) {
            finish()
            return
        }
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_developer_options)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        val master = findViewById<MaterialSwitch>(R.id.swDeveloperMaster)
        master.isChecked = true
        master.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                DeveloperModePrefs.setEnabled(this, false)
                Toast.makeText(this, R.string.developer_options_disabled_toast, Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        val hookAgent = findViewById<MaterialSwitch>(R.id.swDshGptHookAgent)
        hookAgent.isChecked = app.dsh.mobile.engine.ProfilePluginProvisioner.requestedEnabled(
            this, app.dsh.mobile.engine.ProfilePluginProvisioner.DSH_GPT_HOOK_AGENT_PKG,
        )
        hookAgent.setOnCheckedChangeListener { _, checked ->
            app.dsh.mobile.engine.ProfilePluginProvisioner.requestEnabled(
                this, app.dsh.mobile.engine.ProfilePluginProvisioner.DSH_GPT_HOOK_AGENT_PKG, checked,
            )
            Toast.makeText(this, R.string.developer_hook_agent_pending, Toast.LENGTH_SHORT).show()
        }

        val baseUrl = findViewById<EditText>(R.id.etHookBaseUrl)
        val apiKey = findViewById<EditText>(R.id.etHookApiKey)
        val connectionStatus = findViewById<TextView>(R.id.tvHookConnectionStatus)
        val savedConfig = app.dsh.mobile.engine.HookAgentSecrets.read(this)
        baseUrl.setText(savedConfig?.baseUrl ?: "http://127.0.0.1:8766")
        connectionStatus.setText(
            if (savedConfig == null) R.string.developer_hook_not_configured
            else R.string.developer_hook_saved_encrypted,
        )

        fun saveHookConfig(): app.dsh.mobile.engine.HookAgentSecrets.Config? {
            val previous = app.dsh.mobile.engine.HookAgentSecrets.read(this)
            val enteredKey = apiKey.text.toString()
            val effectiveKey = enteredKey.ifBlank { previous?.apiKey.orEmpty() }
            if (effectiveKey.isBlank()) {
                Toast.makeText(this, R.string.developer_hook_key_required, Toast.LENGTH_SHORT).show()
                return null
            }
            return runCatching {
                app.dsh.mobile.engine.HookAgentSecrets.save(
                    this,
                    baseUrl.text.toString().ifBlank { "http://127.0.0.1:8766" },
                    effectiveKey,
                )
                apiKey.text.clear()
                app.dsh.mobile.engine.HookAgentSecrets.read(this)
            }.getOrElse {
                Toast.makeText(this, R.string.developer_hook_invalid_config, Toast.LENGTH_SHORT).show()
                null
            }
        }

        findViewById<android.view.View>(R.id.btnHookSave).setOnClickListener {
            if (saveHookConfig() != null) {
                connectionStatus.setText(R.string.developer_hook_saved_encrypted)
                Toast.makeText(this, R.string.developer_hook_saved_encrypted, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<android.view.View>(R.id.btnHookTest).setOnClickListener {
            val config = saveHookConfig() ?: return@setOnClickListener
            connectionStatus.setText(R.string.developer_hook_testing)
            Thread {
                val message = try {
                    val connection = (URL("${config.baseUrl}/v1/hook/status").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 4000
                        readTimeout = 4000
                        setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                        setRequestProperty("Accept", "application/json")
                    }
                    val code = try { connection.responseCode } finally { connection.disconnect() }
                    when (code) {
                        200 -> R.string.developer_hook_connected
                        401 -> R.string.developer_hook_auth_failed
                        503 -> R.string.developer_hook_api_reachable
                        else -> R.string.developer_hook_http_error
                    }
                } catch (_: Exception) {
                    R.string.developer_hook_unreachable
                }
                runOnUiThread { connectionStatus.setText(message) }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        OrientationPrefs.applyToSettingsPage(this)
    }
}
