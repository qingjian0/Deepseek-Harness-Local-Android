package app.dsh.mobile.engine

/** Pure AGENTS.md user-section operations; kept Android-free for JVM tests. */
internal object UserPromptSectionRenderer {
    const val USER_SECTION_BEGIN = "<!-- >>> user instructions (app never edits below) >>> -->"
    const val USER_SECTION_END = "<!-- <<< user instructions <<< -->"
    private const val PLACEHOLDER =
        "<!-- Add your own instructions below this line. DSH Mobile never overwrites\n" +
            "     this section, and upgrades keep it as-is. Ask the AI to write here for you. -->"
    private const val SEED_MARKER_PREFIX = "<!-- dsh-android AGENTS seed v"

    fun extractLegacyContent(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        val begin = text.indexOf(USER_SECTION_BEGIN)
        if (begin < 0) {
            if (text.contains(SEED_MARKER_PREFIX)) return null
            return text.trim().ifEmpty { null }
        }
        val bodyStart = text.indexOf('\n', begin).let { if (it < 0) text.length else it + 1 }
        val end = text.indexOf(USER_SECTION_END, bodyStart)
        val body = if (end < 0) text.substring(bodyStart) else text.substring(bodyStart, end)
        return body.replace(PLACEHOLDER, "").trim().ifEmpty { null }
    }

    fun suffixAfterSection(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val begin = text.indexOf(USER_SECTION_BEGIN)
        if (begin < 0) return ""
        val end = text.indexOf(USER_SECTION_END, begin + USER_SECTION_BEGIN.length)
        if (end < 0) return ""
        return text.substring(end + USER_SECTION_END.length)
    }

    fun render(existing: String, enabled: Boolean, content: String): String {
        val begin = existing.indexOf(USER_SECTION_BEGIN)
        if (begin < 0) {
            if (!enabled) return existing
            return existing.trimEnd() + "\n\n" + userSection(content)
        }
        val end = existing.indexOf(USER_SECTION_END, begin + USER_SECTION_BEGIN.length)
        val endExclusive = if (end < 0) existing.length else end + USER_SECTION_END.length
        val prefix = existing.substring(0, begin)
        val suffix = if (end < 0) "" else existing.substring(endExclusive)
        if (!enabled) return if (suffix.isBlank()) prefix.trimEnd() + "\n" else prefix + suffix
        return prefix + userSection(content) + suffix
    }

    private fun userSection(content: String): String = buildString {
        append(USER_SECTION_BEGIN).append('\n')
        val body = content.trim()
        if (body.isEmpty()) append(PLACEHOLDER).append('\n') else append(body).append('\n')
        append(USER_SECTION_END).append('\n')
    }
}
