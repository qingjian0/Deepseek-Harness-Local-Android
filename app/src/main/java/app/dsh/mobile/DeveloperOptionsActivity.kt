package app.dsh.mobile

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Entry point for optional developer-facing tools.
 *
 * The master switch hides this page again. It deliberately does not grant
 * Root/Shizuku or bypass CapabilityGate; individual tools must enforce their
 * own authorization at the point of execution.
 */
class DeveloperOptionsActivity : ThemedActivity() {
    override val usesSettingsBackground: Boolean get() = true

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }
        val header = TextView(this).apply {
            text = getString(R.string.developer_options_intro)
            textSize = 14f
            setTextColor(0xff888888.toInt())
            setPadding(dp(4), dp(8), dp(4), dp(20))
        }
        root.addView(header)

        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(resolveSurfaceColor())
            strokeWidth = dp(1)
            strokeColor = resolveStrokeColor()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        labels.addView(TextView(this).apply {
            text = getString(R.string.developer_options_title)
            textSize = 16f
            setTextColor(resolveTextColor())
        })
        labels.addView(TextView(this).apply {
            text = getString(R.string.developer_options_enabled_sub)
            textSize = 12f
            setTextColor(resolveSecondaryTextColor())
            setPadding(0, dp(4), 0, 0)
        })
        val master = MaterialSwitch(this).apply {
            isChecked = true
            contentDescription = getString(R.string.developer_options_title)
        }
        row.addView(labels)
        row.addView(master)
        card.addView(row)
        root.addView(card)

        val note = TextView(this).apply {
            text = getString(R.string.developer_options_security_note)
            textSize = 12f
            setTextColor(resolveSecondaryTextColor())
            setPadding(dp(4), dp(16), dp(4), 0)
        }
        root.addView(note)

        val scroll = android.widget.ScrollView(this).apply {
            addView(root)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(20), dp(10))
        }
        toolbar.addView(TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(resolveTextColor())
            gravity = Gravity.CENTER
            setPadding(0, 0, dp(16), 0)
            setOnClickListener { finish() }
            contentDescription = getString(R.string.settings_back)
        })
        toolbar.addView(TextView(this).apply {
            text = getString(R.string.developer_options_title)
            textSize = 20f
            setTextColor(resolveTextColor())
        })
        page.addView(toolbar)
        page.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        setContentView(page)

        master.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                DeveloperModePrefs.setEnabled(this, false)
                Toast.makeText(this, R.string.developer_options_disabled_toast, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        OrientationPrefs.applyToSettingsPage(this)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun resolveColor(attr: Int, fallback: Int): Int {
        val typed = android.util.TypedValue()
        return if (theme.resolveAttribute(attr, typed, true)) {
            if (typed.resourceId != 0) runCatching {
                androidx.core.content.ContextCompat.getColor(this, typed.resourceId)
            }.getOrDefault(typed.data) else typed.data
        } else fallback
    }

    private fun resolveSurfaceColor() = resolveColor(com.google.android.material.R.attr.colorSurface, 0xff202124.toInt())
    private fun resolveStrokeColor() = resolveColor(com.google.android.material.R.attr.colorOutline, 0xff555555.toInt())
    private fun resolveTextColor() = resolveColor(android.R.attr.textColorPrimary, 0xffffffff.toInt())
    private fun resolveSecondaryTextColor() = resolveColor(android.R.attr.textColorSecondary, 0xffaaaaaa.toInt())
}
