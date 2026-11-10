package app.dsh.mobile

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.UserPromptRepository
import app.dsh.mobile.engine.UserPromptContextAdapter

/**
 * 用户自定义指令页（v1.3.2，issue #19）。
 *
 * ## 背景
 * 用户希望「在现有提示词之上补写自己的指令」。引擎只加载
 * `$DSH_HOME/AGENTS.md` 这一个 user-global 指令文件（不会加载同目录其它文件名）。
 * 用户指令现在以 SharedPreferences 独立持久化，AGENTS.md 只是运行时注入结果；
 * 上游环境模板升级不会覆盖用户配置。
 *
 * 页面底部说明用户指令独立保存，并由应用注入 AGENTS.md。
 */
class UserPromptActivity : ThemedActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v1.4.18：跟随应用朝向偏好（横屏下编辑区域更宽，必要时仍可滚动）
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_user_prompt)

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }

        val editor = findViewById<EditText>(R.id.editorPrompt)
        val hint = findViewById<TextView>(R.id.txtPath)

        // 首次打开编辑器也执行迁移，避免引擎尚未启动时覆盖旧 AGENTS.md 内容。
        UserPromptRepository.migrateLegacyIfNeeded(this)
        val current = UserPromptRepository.content(this)
        editor.setText(current)
        if (current.isNullOrEmpty()) {
            editor.hint = getString(R.string.user_prompt_placeholder)
        }
        hint.text = getString(R.string.user_prompt_path)

        findViewById<android.view.View>(R.id.btnSave).setOnClickListener {
            val text = editor.text.toString()
            if (!UserPromptRepository.saveContent(this, text)) {
                Toast.makeText(this, getString(R.string.user_prompt_toggle_failed), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            UserPromptContextAdapter.refreshAsync(this)
            Toast.makeText(this, getString(R.string.user_prompt_saved), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** 回到本页时重新对齐朝向：外观页可能刚改过开关/偏好。 */
    override fun onResume() {
        super.onResume()
        // 从外观页返回时偏好可能已变（开关/朝向），重新对齐一次；值未变时是空操作
        OrientationPrefs.applyToSettingsPage(this)
    }
}
