package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-owned agent instructions, deployed as build artifacts (v1.4.0).
 *
 * ## What changed and why
 * Up to v1.3.3 the app wrote its environment manual into `$DSH_HOME/AGENTS.md`, the
 * engine's only user-global *instruction* file. That file is injected as a **user-role**
 * message whose preamble says it does not override system/developer instructions, and
 * which is (a) outranked by any project-level `AGENTS.md`, (b) first to be dropped when
 * the 64KiB instruction budget overflows, and (c) shared with the user's own text —
 * which forced marker parsing, split logic and upgrade merge heuristics.
 *
 * Now the app text is a **system prompt section** registered by
 * `app/src/main/assets/android-plugins/mobile-instructions.mjs`, reading
 * `$DSH_HOME/mobile/resident.md`. This object only deploys that bundle:
 *
 *   assets/mobile/resident.md          -> $DSH_HOME/mobile/resident.md
 *   assets/mobile/docs (one .md each)  -> $DSH_HOME/mobile/docs/
 *   assets/mobile/skills/<id>/SKILL.md -> $DSH_HOME/skills/<id>/SKILL.md
 *
 * (No "slash-star" sequences in this comment: Kotlin block comments nest, and a glob
 * such as docs-slash-star-dot-md would silently open a second comment and swallow the
 * rest of the file — CI caught exactly that on the first push.)
 *
 * The bundle is compiled by `tools/instructions/build.mjs` from a single authored
 * source; `tools/instructions/ci/check.mjs` fails CI when the committed assets drift
 * from that source. `$DSH_HOME/AGENTS.md` is left 100% to the user.
 *
 * Skills land in `$DSH_HOME/skills` because that is the skill provider's default user
 * root — no profile edit is needed for them to show up in the catalog.
 *
 * ## Safety rules for the takeover
 * 1. Never treat "unreadable" as "absent": a previous install's uid or a bad SELinux
 *    label makes the file exist but fail to read. Writing over it would destroy user
 *    data, so an unreadable file is skipped and retried on the next boot (the boot
 *    step runs after the ownership self-heal for exactly this reason).
 * 2. Only take over after this build actually ships the plugin that replaces the old
 *    channel; otherwise the agent would lose the environment manual entirely.
 * 3. A failed archive aborts the rewrite — the backup exists precisely for that case.
 */
object MobileInstructions {

    private const val TAG = "MobileInstructions"

    private const val ASSET_ROOT = "mobile"
    private const val FILE_NAME = "AGENTS.md"
    private const val HOME_DIR = "mobile"

    /** Presence of this asset means the build can replace the old instruction channel. */
    private const val PLUGIN_ASSET = "android-plugins/mobile-instructions.mjs"

    /** Marker the app writes into the user-owned file once it has taken over the manual. */
    private const val OWNED_MARK =
        "<!-- dsh-mobile: this file is user-owned. The app's environment manual lives in the system prompt. -->"

    // v1.3.2 - v1.3.3 split markers, still present on devices upgrading from those versions.
    private const val LEGACY_SEED_PREFIX = "<!-- dsh-android AGENTS seed v"
    private const val LEGACY_USER_BEGIN = "<!-- >>> user instructions (app never edits below) >>> -->"
    private const val LEGACY_USER_END = "<!-- <<< user instructions <<< -->"

    /** Pointer we leave behind after archiving a legacy seed: not user content. */
    private val LEGACY_HINT = Regex("<!--\\s*Legacy content was archived to [^>]*-->")

    /** The empty-section placeholder written by v1.3.2: not user content, must not survive. */
    private val LEGACY_PLACEHOLDER = Regex(
        "<!--\\s*Add your own instructions below this line\\.[\\s\\S]*?-->",
    )

    /** Idempotent boot step: deploy the bundle, then hand AGENTS.md over to the user. */
    fun ensure(ctx: Context) {
        val home = EngineConfig.dshHome(ctx)
        runCatching { deployBundle(ctx, home) }
            .onFailure { Log.w(TAG, "bundle deploy failed: ${it.message}") }
        runCatching { migrateAgentsMd(ctx, home) }
            .onFailure { Log.w(TAG, "AGENTS.md migration failed: ${it.message}") }
    }

    /** The whole file is user content now; null means "nothing written yet". */
    fun readUserInstructions(ctx: Context): String? {
        val file = File(EngineConfig.dshHome(ctx), FILE_NAME)
        val text = runCatching { if (file.isFile) file.readText() else null }.getOrNull() ?: return null
        return text.replace(OWNED_MARK, "").replace(LEGACY_HINT, "").trim().ifEmpty { null }
    }

    /** Replaces the file. The app's environment manual is not stored here any more. */
    fun writeUserInstructions(ctx: Context, content: String?) {
        val file = File(EngineConfig.dshHome(ctx), FILE_NAME)
        val body = content?.trim().orEmpty()
        writeAtomic(file, if (body.isEmpty()) OWNED_MARK + "\n" else body + "\n")
    }

    // ---------------------------------------------------------------- deploy

    private fun deployBundle(ctx: Context, home: File) {
        val mobile = File(home, HOME_DIR)
        deploy(ctx, "$ASSET_ROOT/resident.md", File(mobile, "resident.md"))
        deployDocs(ctx, mobile)
        deploySkills(ctx, home)
    }

    /**
     * Docs are app-owned, but the directory is also the one the manual tells the model to
     * read, so pruning is ledger-based: only files this app deployed before are removed.
     */
    private fun deployDocs(ctx: Context, mobile: File) {
        val names = assetChildren(ctx, "$ASSET_ROOT/docs")
        val dir = File(mobile, "docs")
        names.forEach { deploy(ctx, "$ASSET_ROOT/docs/$it", File(dir, it)) }

        val ledger = File(mobile, "deployed-docs.txt")
        val previous = readLedger(ledger)
        (previous - names.toSet()).forEach { stale ->
            if (stale.endsWith(".md") && !stale.contains('/') && !stale.contains("..")) File(dir, stale).delete()
        }
        writeLedger(ledger, names)
    }

    /**
     * Skills go to `$DSH_HOME/skills` (the provider's default user root). Only directories
     * recorded in the previous deployment ledger are pruned, so user-authored skills in
     * that shared directory are never touched.
     */
    private fun deploySkills(ctx: Context, home: File) {
        val ids = assetChildren(ctx, "$ASSET_ROOT/skills")
        val targetRoot = File(home, "skills")
        val ledger = File(File(home, HOME_DIR), "deployed-skills.txt")
        val previous = readLedger(ledger)

        ids.forEach { id -> deploy(ctx, "$ASSET_ROOT/skills/$id/SKILL.md", File(File(targetRoot, id), "SKILL.md")) }

        val safeName = Regex("[a-z0-9][a-z0-9-]*")
        (previous - ids.toSet()).forEach { stale ->
            if (safeName.matches(stale)) File(targetRoot, stale).deleteRecursively()
        }
        writeLedger(ledger, ids)
    }

    private fun assetChildren(ctx: Context, path: String): List<String> =
        runCatching { ctx.assets.list(path)?.sorted()?.toList() ?: emptyList() }.getOrDefault(emptyList())

    /**
     * Copy one asset to disk when it differs (same idea as the plugin deployer in
     * `EngineConfig`): a stale file may be owned by a previous install's uid, and the app
     * can still delete it through the parent directory before rewriting.
     */
    private fun deploy(ctx: Context, assetPath: String, target: File) {
        val text = runCatching {
            ctx.assets.open(assetPath).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return
        if (target.isFile && runCatching { target.readText() }.getOrNull() == text) return
        runCatching {
            target.parentFile?.mkdirs()
            target.delete()
            target.writeText(text)
        }.onFailure { Log.w(TAG, "deploy $assetPath failed: ${it.message}") }
    }

    private fun readLedger(file: File): Set<String> = runCatching {
        file.readText().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }.getOrDefault(emptySet())

    private fun writeLedger(file: File, values: List<String>) {
        runCatching {
            file.parentFile?.mkdirs()
            writeAtomic(file, values.joinToString("\n") + "\n")
        }
    }

    // ------------------------------------------------------------- migration

    /**
     * Turn a legacy `AGENTS.md` into a user-owned file:
     *  - split layout (v1.3.2+) -> keep only the user section body;
     *  - bare app seed (older)  -> nothing in it belongs to the app any more, archive it;
     *  - no app markers at all  -> user-authored, leave untouched.
     * The original is always archived first, and a failed archive aborts the rewrite.
     */
    private fun migrateAgentsMd(ctx: Context, home: File) {
        val file = File(home, FILE_NAME)

        if (!file.exists()) {
            if (shipsPlugin(ctx)) writeAtomic(file, OWNED_MARK + "\n")
            return
        }

        val existing = runCatching { file.readText() }.getOrNull()
        if (existing == null) {
            // Exists but unreadable: old uid / bad label. Never overwrite blind.
            Log.w(TAG, "$FILE_NAME exists but is unreadable; leaving it alone (retry next boot)")
            return
        }
        if (existing.contains(OWNED_MARK)) return
        if (!shipsPlugin(ctx)) {
            Log.w(TAG, "plugin not packaged in this build; keeping legacy $FILE_NAME untouched")
            return
        }

        val userSection = extractLegacyUserSection(existing)
        when {
            userSection != null -> {
                val backup = archive(file, existing) ?: return
                writeAtomic(file, userSection.ifEmpty { OWNED_MARK } + "\n")
                Log.i(TAG, "$FILE_NAME migrated: user section kept (${userSection.length} chars, backup ${backup.name})")
            }

            existing.contains(LEGACY_SEED_PREFIX) -> {
                val backup = archive(file, existing) ?: return
                writeAtomic(
                    file,
                    OWNED_MARK + "\n" +
                        "<!-- Legacy content was archived to ${backup.name}; copy back anything you had added there. -->\n",
                )
                Log.i(TAG, "$FILE_NAME migrated: legacy app seed removed (backup ${backup.name})")
            }

            else -> Log.i(TAG, "$FILE_NAME has no app markers; left untouched")
        }
    }

    /** User section body, with the empty-section placeholder stripped; null when absent. */
    private fun extractLegacyUserSection(text: String): String? {
        val begin = text.indexOf(LEGACY_USER_BEGIN)
        if (begin < 0) return null
        val bodyStart = text.indexOf('\n', begin).let { if (it < 0) text.length else it + 1 }
        val end = text.indexOf(LEGACY_USER_END, bodyStart)
        val body = if (end < 0) text.substring(bodyStart) else text.substring(bodyStart, end)
        return body.replace(LEGACY_PLACEHOLDER, "").trim()
    }

    private fun shipsPlugin(ctx: Context): Boolean =
        runCatching { ctx.assets.open(PLUGIN_ASSET).close() }.isSuccess

    // ------------------------------------------------------------------ io

    /**
     * Archive the current file next to it. Returns the backup file, or null when the
     * archive could not be written — callers must abort the destructive step then.
     */
    private fun archive(file: File, text: String): File? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var target = File(file.parentFile, "${FILE_NAME}.bak-legacy-$stamp")
        var index = 2
        while (target.exists()) {
            target = File(file.parentFile, "${FILE_NAME}.bak-legacy-$stamp-$index")
            index++
        }
        val ok = runCatching { writeAtomic(target, text) }.getOrDefault(false)
        if (!ok) {
            Log.w(TAG, "archive $FILE_NAME failed; aborting the destructive migration step")
            return null
        }
        return target
    }

    /**
     * Write through a temp file + rename so a killed process never leaves a truncated
     * file behind. Android allows rename within the same directory.
     */
    private fun writeAtomic(file: File, text: String): Boolean {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        return runCatching {
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
            true
        }.getOrElse {
            runCatching { tmp.delete() }
            Log.w(TAG, "write ${file.name} failed: ${it.message}")
            false
        }
    }
}
