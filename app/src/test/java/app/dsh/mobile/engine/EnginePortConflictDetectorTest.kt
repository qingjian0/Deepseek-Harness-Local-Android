package app.dsh.mobile.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnginePortConflictDetectorTest {
    @Test fun rotatesOnlyWhenRequiredWebServerAndPortEvidenceAgree() {
        assertTrue(
            EnginePortConflictDetector.shouldRotate(
                missingServices = listOf("webServer"),
                failedPluginIds = listOf("webserver"),
                logHasPortConflict = true,
            ),
        )
    }

    @Test fun doesNotRotateForUnrelatedPluginFailure() {
        assertFalse(
            EnginePortConflictDetector.shouldRotate(
                missingServices = listOf("workspaceRegistry"),
                failedPluginIds = listOf("workspace"),
                logHasPortConflict = true,
            ),
        )
    }

    @Test fun doesNotRotateWhenWebServerFailsWithoutBindEvidence() {
        assertFalse(
            EnginePortConflictDetector.shouldRotate(
                missingServices = listOf("webServer"),
                failedPluginIds = listOf("webserver"),
                logHasPortConflict = false,
            ),
        )
    }

    @Test fun doesNotRotateForLogEvidenceWithoutRequiredWebServerFailure() {
        assertFalse(
            EnginePortConflictDetector.shouldRotate(
                missingServices = emptyList(),
                failedPluginIds = emptyList(),
                logHasPortConflict = true,
            ),
        )
    }
}
