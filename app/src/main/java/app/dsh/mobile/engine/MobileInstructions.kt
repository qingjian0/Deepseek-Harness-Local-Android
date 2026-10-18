package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

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
 *   assets/mobile/resident.md        -> $DSH_HOME/mobile/resident.md
 *   assets/mobile/docs/*.md          -> $DSH_HOME/mobile/docs/
 *   assets/mobile/skills/<id>/*.md   -> $DSH_HOME/skills/<id>/SKILL.md
 *
 * The bundle is compiled by `tools/instructions/build.mjs` from a single authored
 * source; `tools/instructions/ci/check.mjs` fails CI when the committed assets drift
 * from that source. `$DSH_HOME/AGENTS.md` is left 100% to the user.
 *
 * Skills land in `$DSH_HOME/skills` because that is the skill provider's default user
 * root — no profile edit is needed for them to show up in the catalog.
 */
object MobileInstructions {

    private const val TAG = "MobileInstructions"

    private const val ASSET_ROOT = "mobile"
    private const val FILE_NAME = "AGENTS.md"
    private const val HOME_DIR = "mobile"

    /** Marker the app writes into the user-owned file once it has taken over the manual. */
    private const val OWNED_MARK =
        "<!-- dsh-mobile: this file is user-owned. The app's environment manual lives in the system prompt. -->"

    // v1.3.2 - v1.3.3 split markers, still present on devices upgrading from those versions.
    private const val LEGACY_SEED_PREFIX = "<!-- dsh-android AGENTS seed v"
    private const val LEGACY_USER_BEGIN = "<!-- >>> user instructions (app never edits below) >>> -->"
    private const val LEGACY_USER_END = "<!-- <<< user instructions <<< -->"

    /** Idempotent boot step: deploy the bundle, then hand AGENTS.md over to the user. */
    fun ensure(ctx: Context) {
        val home = File(EngineConfig.dshHome(ctx))
        runCatching { deployBundle(ctx, home) }
            .onFailure { Log.w(TAG, "bundle deploy failed: ${it.message}") }
        runCatching { migrateAgentsMd(home) }
            .onFailure { Log.w(TAG, "AGENTS.md migration failed: ${it.message}") }
    }

    /** The whole file is user content now; null means "nothing written yet". */
    fun readUserInstructions(ctx: Context): String? {
        val file = File(EngineConfig.dshHome(ctx), FILE_NAME)
        val text = runCatching { if (file.isFile) file.readText() else null }.getOrNull() ?: return null
        return text.replace(OWNED_MARK, "").trim().ifEmpty { null }
    }

    /** Replaces the file. The app's environment manual is not stored here any more. */
    fun writeUserInstructions(ctx: Context, content: String?) {
        val file = File(EngineConfig.dshHome(ctx), FILE_NAME)
        val body = content?.trim().orEmpty()
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(if (body.isEmpty()) OWNED_MARK + "\n" else body + "\n")
        }.onFailure { Log.w(TAG, "write AGENTS.md failed: ${it.message}") }
    }

    // ---------------------------------------------------------------- deploy

    private fun deployBundle(ctx: Context, home: File) {
        val mobile = File(home, HOME_DIR)
        deploy(ctx, "$ASSET_ROOT/resident.md", File(mobile, "resident.md"))
        deployDocs(ctx, mobile)
        deploySkills(ctx, home)
    }

    private fun deployDocs(ctx: Context, mobile: File) {
        val names = assetChildren(ctx, "$ASSET_ROOT/docs")
        val dir = File(mobile, "docs")
        names.forEach { deploy(ctx, "$ASSET_ROOT/docs/$it", File(dir, it)) }
        // The directory is app-owned: drop pages we shipped before and no longer compile.
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".md") && f.name !in names) f.delete()
        }
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
        val previous = runCatching {
            ledger.readText().lineSequence().filter { it.isNotBlank() }.toSet()
        }.getOrDefault(emptySet())

        ids.forEach { id -> deploy(ctx, "$ASSET_ROOT/skills/$id/SKILL.md", File(File(targetRoot, id), "SKILL.md")) }

        val safeName = Regex("[a-z0-9][a-z0-9-]*")
        (previous - ids.toSet()).forEach { stale ->
            if (safeName.matches(stale)) File(targetRoot, stale).deleteRecursively()
        }
        runCatching {
            ledger.parentFile?.mkdirs()
            ledger.writeText(ids.joinToString("\n") + "\n")
        }
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

    // ------------------------------------------------------------- migration

    /**
     * Turn a legacy `AGENTS.md` into a user-owned file:
     *  - split layout (v1.3.2+) -> keep only the user section body;
     *  - bare app seed (older)  -> nothing in it belongs to the user, clear it;
     *  - no app markers at all  -> user-authored, leave untouched.
     * The original file is always backed up first.
     */
    private fun migrateAgentsMd(home: File) {
        val file = File(home, FILE_NAME)
        val existing = runCatching { if (file.isFile) file.readText() else null }.getOrNull()
        if (existing == null) {
            file.writeText(OWNED_MARK + "\n")
            return
        }
        if (existing.contains(OWNED_MARK)) return

        val userSection = extractLegacyUserSection(existing)
        when {
            userSection != null -> {
                backup(file, existing)
                file.writeText(userSection.ifEmpty { OWNED_MARK } + "\n")
                Log.i(TAG, "AGENTS.md migrated: user section kept (${userSection.length} chars)")
            }

            existing.contains(LEGACY_SEED_PREFIX) -> {
                backup(file, existing)
                file.writeText(OWNED_MARK + "\n")
                Log.i(TAG, "AGENTS.md migrated: legacy app seed removed (backup kept)")
            }

            else -> Log.i(TAG, "AGENTS.md has no app markers; left untouched")
        }
    }

    private fun extractLegacyUserSection(text: String): String? {
        val begin = text.indexOf(LEGACY_USER_BEGIN)
        if (begin < 0) return null
        val bodyStart = text.indexOf('\n', begin).let { if (it < 0) text.length else it + 1 }
        val end = text.indexOf(LEGACY_USER_END, bodyStart)
        val body = if (end < 0) text.substring(bodyStart) else text.substring(bodyStart, end)
        return body.trim()
    }

    private fun backup(file: File, text: String) {
        var target = File(file.parentFile, "${FILE_NAME}.bak-legacy")
        var index = 2
        while (target.exists()) {
            target = File(file.parentFile, "${FILE_NAME}.bak-legacy-$index")
            index++
        }
        runCatching { target.writeText(text) }
            .onSuccess { Log.i(TAG, "AGENTS.md backed up to ${target.name}") }
            .onFailure { Log.w(TAG, "AGENTS.md backup failed: ${it.message}") }
    }
}
