package app.dsh.mobile

import android.content.Context
import android.os.Bundle
import android.widget.ImageView
import android.widget.EditText
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import app.dsh.mobile.engine.EnginePortManager
import android.widget.Toast
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
        findViewById<android.widget.Button>(R.id.btnEnginePorts).setOnClickListener { showPortSettings() }
        refreshPortSummary()
        master.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                DeveloperModePrefs.setEnabled(this, false)
                Toast.makeText(this, R.string.developer_options_disabled_toast, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun refreshPortSummary() {
        val c = EnginePortManager.config(this)
        val actual = EnginePortManager.currentPort(this)
        val mode = if (c.mode == EnginePortManager.Mode.AUTO) "自动端口段 ${c.start}-${c.end}（搜索至 ${c.searchEnd}）" else "精确端口 ${c.exact}"
        val reason = EnginePortManager.lastReason(this)
        findViewById<android.widget.TextView>(R.id.txtEnginePortSummary).text = "$mode · 当前实际端口：$actual · $reason"
    }

    private fun showPortSettings() {
        val c = EnginePortManager.config(this)
        val choices = arrayOf("自动端口段", "精确端口")
        AlertDialog.Builder(this).setTitle(R.string.developer_port_title)
            .setItems(choices) { _, which ->
                val exactMode = which == 1
                val initial = if (exactMode) c.exact.toString() else "${c.start}-${c.end},${c.searchEnd}"
                val input = EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_TEXT
                    setSingleLine(true); setText(initial)
                    hint = if (exactMode) "8080" else "3080-3090,8000"
                }
                AlertDialog.Builder(this)
                    .setTitle(if (exactMode) R.string.developer_port_exact else R.string.developer_port_auto)
                    .setView(input).setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        runCatching {
                            val value = input.text.toString().trim()
                            if (exactMode) EnginePortManager.setExact(this, value.toInt()) else {
                                val parts = value.split(',', '-').map { it.trim().toInt() }
                                require(parts.size == 3) { "格式应为 起始-结束,搜索上限" }
                                EnginePortManager.setAuto(this, parts[0], parts[1], parts[2])
                            }
                            refreshPortSummary()
                            Toast.makeText(this, R.string.developer_port_saved_restart, Toast.LENGTH_LONG).show()
                        }.onFailure { Toast.makeText(this, it.message ?: getString(R.string.developer_port_invalid), Toast.LENGTH_LONG).show() }
                    }.show()
            }.show()
    }

    override fun onResume() {
        super.onResume()
        OrientationPrefs.applyToSettingsPage(this)
    }
}
