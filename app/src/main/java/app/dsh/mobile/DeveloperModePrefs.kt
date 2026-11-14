package app.dsh.mobile

import android.content.Context

/**
 * Hidden developer-options gate.
 *
 * This flag only controls discovery and access to the developer UI. It is not
 * an authorization grant for privileged operations; each operation must keep
 * using its existing permission and capability checks.
 */
object DeveloperModePrefs {
    private const val PREFS = "developer_options"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
