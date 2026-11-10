package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserPromptSectionRendererTest {
    private val begin = UserPromptSectionRenderer.USER_SECTION_BEGIN
    private val end = UserPromptSectionRenderer.USER_SECTION_END

    @Test fun disabledRemovesBlockAndPreservesTrailingContent() {
        val source = "APP\n\n" + begin + "\nsecret\n" + end + "\n\nTAIL\n"
        val rendered = UserPromptSectionRenderer.render(source, false, "secret")
        assertFalse(rendered.contains(begin))
        assertFalse(rendered.contains("secret"))
        assertTrue(rendered.endsWith("\n\nTAIL\n"))
    }

    @Test fun enabledReplacesOnlyBlockAndPreservesSuffix() {
        val source = "APP\n\n" + begin + "\nold\n" + end + "\n\nTAIL\n"
        val rendered = UserPromptSectionRenderer.render(source, true, "new instruction")
        assertTrue(rendered.contains("new instruction"))
        assertFalse(rendered.contains("\nold\n"))
        assertTrue(rendered.endsWith("\n\nTAIL\n"))
        assertTrue(rendered.startsWith("APP\n\n"))
    }

    @Test fun renderingIsIdempotent() {
        val source = "APP\n"
        val once = UserPromptSectionRenderer.render(source, true, "hello")
        assertEquals(once, UserPromptSectionRenderer.render(once, true, "hello"))
    }

    @Test fun disabledDoesNotAddPlaceholderWithoutSection() {
        val source = "APP\nTAIL\n"
        assertEquals(source, UserPromptSectionRenderer.render(source, false, "ignored"))
    }

    @Test fun legacyUnmarkedFileIsExtracted() {
        assertEquals("legacy text", UserPromptSectionRenderer.extractLegacyContent("  legacy text  "))
    }

    @Test fun managedSeedWithoutUserSectionIsNotLegacyUserContent() {
        assertEquals(null, UserPromptSectionRenderer.extractLegacyContent(
            "<!-- dsh-android AGENTS seed v54 -->\napp guide"))
    }

    @Test fun emptyLegacySectionReturnsNull() {
        assertEquals(null, UserPromptSectionRenderer.extractLegacyContent("APP\n" + begin + "\n" + end))
    }
}
