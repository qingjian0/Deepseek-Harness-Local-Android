package app.dsh.mobile.engine

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Coordinates legacy migration, upstream seed updates and local user-section rendering. */
object UserPromptContextAdapter {
    private const val TAG = "UserPromptContext"
    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "user-prompt-render").apply { isDaemon = true }
    }

    fun ensure(context: Context) = refresh(context)

    fun refreshAsync(context: Context) {
        val appContext = context.applicationContext
        writer.execute {
            runCatching { refresh(appContext) }
                .onFailure { Log.e(TAG, "AGENTS.md refresh failed", it) }
        }
    }

    fun refresh(context: Context) {
        synchronized(lock) {
            val appContext = context.applicationContext
            UserPromptRepository.migrateLegacyIfNeeded(appContext)
            val file = File(EngineConfig.dshHome(appContext), "AGENTS.md")
            val hasAtomicData = file.isFile || File(file.absolutePath + ".bak").isFile
            val before = if (hasAtomicData) readAtomically(file) else null
            val suffix = UserPromptSectionRenderer.suffixAfterSection(before)

            // Keep upstream environment template generation and version upgrades intact.
            AgentContextSeed.ensure(appContext)
            if (!file.isFile) throw IllegalStateException("AGENTS.md was not created")
            var current = readAtomically(file)
            if (suffix.isNotEmpty() && !current.endsWith(suffix)) current += suffix

            val rendered = UserPromptSectionRenderer.render(
                current,
                UserPromptRepository.isEnabled(appContext),
                UserPromptRepository.content(appContext),
            )
            if (rendered != current) writeAtomically(file, rendered)
        }
    }

    private fun readAtomically(file: File): String =
        AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            atomic.finishWrite(stream)
            stream = null
        } catch (failure: Throwable) {
            if (stream != null) atomic.failWrite(stream)
            throw failure
        }
    }
}
