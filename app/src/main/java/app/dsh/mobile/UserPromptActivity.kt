package app.dsh.mobile

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.MobileInstructions

/**
 * 用户自定义指令页（v1.3.2，issue #19）。
 *
 * ## 背景
 * 用户希望「在现有环境说明之上补写自己的指令」。自 v1.4.0 起，App 的环境说明
 * 不再写进这个文件 —— 它由 mobile-instructions 插件注册成 system prompt section，
 * 因此 `$DSH_HOME/AGENTS.md` 整份归用户：没有标记、没有分段、升级永不触碰。
 * 本页编辑的就是整份文件，所见即所存。
 */
class UserPromptActivity : Activity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_user_prompt)

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }

        val editor = findViewById<EditText>(R.id.editorPrompt)
        val hint = findViewById<TextView>(R.id.txtPath)

        // 载入现有用户段（未写过则为空）
        val current = MobileInstructions.readUserInstructions(this)
        editor.setText(current.orEmpty())
        if (current.isNullOrEmpty()) {
            editor.hint = getString(R.string.user_prompt_placeholder)
        }
        hint.text = getString(R.string.user_prompt_path)

        findViewById<android.view.View>(R.id.btnSave).setOnClickListener {
            val text = editor.text.toString()
            MobileInstructions.writeUserInstructions(this, text.ifBlank { null })
            Toast.makeText(this, getString(R.string.user_prompt_saved), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
