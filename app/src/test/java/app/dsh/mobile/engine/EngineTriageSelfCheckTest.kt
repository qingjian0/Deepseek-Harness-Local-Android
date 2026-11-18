package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTriageSelfCheckTest {

    private fun report(vararg items: SelfCheck.Item) =
        SelfCheck.Report(items.toList(), startedAt = 1L, finishedAt = 2L)


    @Test
    fun damagedSharedLibraryIsActionableRuntimeFailure() {
        val result = EngineTriage.diagnose(
            EngineTriage.Facts(
                runtimeIntact = false,
                runtimeMissing = listOf("libz.so.1: file too small"),
            ),
        )

        assertEquals(EngineTriage.Cause.RUNTIME_MISSING, result.cause)
        assertTrue(result.fixes.any { it.kind == EngineTriage.Fix.Kind.REINSTALL_RUNTIME })
    }

    @Test
    fun optionalAllFilesAccessWarningDoesNotBecomeStorageFailure() {
        val result = EngineTriage.diagnose(
            EngineTriage.Facts(
                neverBooted = true,
                selfCheck = report(
                    SelfCheck.Item(
                        "storage.allfiles",
                        "All files access",
                        SelfCheck.Item.Status.WARN,
                        "not granted — data stays in app-private storage (lost on uninstall)",
                    ),
                ),
            ),
        )

        assertEquals(EngineTriage.Cause.UNKNOWN, result.cause)
    }

    @Test
    fun missingProfileOnFirstBootDoesNotBecomeProfileCorruption() {
        val result = EngineTriage.diagnose(
            EngineTriage.Facts(
                neverBooted = true,
                selfCheck = report(
                    SelfCheck.Item(
                        "profile",
                        "Plugin profile",
                        SelfCheck.Item.Status.WARN,
                        "not created yet",
                    ),
                ),
            ),
        )

        assertEquals(EngineTriage.Cause.UNKNOWN, result.cause)
    }

    @Test
    fun missingProfileAfterSuccessfulBootRemainsActionable() {
        val result = EngineTriage.diagnose(
            EngineTriage.Facts(
                neverBooted = false,
                selfCheck = report(
                    SelfCheck.Item(
                        "profile",
                        "Plugin profile",
                        SelfCheck.Item.Status.WARN,
                        "not created yet",
                    ),
                ),
            ),
        )

        assertEquals(EngineTriage.Cause.PROFILE_CORRUPT, result.cause)
    }

    @Test
    fun actualProfileParseFailureStillMapsToProfileCorruption() {
        val result = EngineTriage.diagnose(
            EngineTriage.Facts(
                neverBooted = true,
                selfCheck = report(
                    SelfCheck.Item(
                        "profile",
                        "Plugin profile",
                        SelfCheck.Item.Status.FAIL,
                        "unparseable: malformed package.json",
                    ),
                ),
            ),
        )

        assertEquals(EngineTriage.Cause.PROFILE_CORRUPT, result.cause)
    }
}
