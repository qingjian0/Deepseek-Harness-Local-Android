package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnginePortManagerTest {
    @Test fun reservedInternalPortsAreSkipped() {
        assertFalse(EnginePortManager.isCandidateAvailable(3081) { it != 3083 })
        assertTrue(EnginePortManager.isCandidateAvailable(3084) { true })
    }

    @Test fun shizukuCompanionPortMustAlsoBeFree() {
        assertFalse(EnginePortManager.isCandidateAvailable(3080) { it != 3082 })
    }

    @Test fun fallbackSegmentAdvancesByOneHundred() {
        val start = 3080
        val end = 3090
        val upper = 8000
        val firstFree = (start..end).firstOrNull { EnginePortManager.isCandidateAvailable(it) { p -> p >= 3180 } }
        assertEquals(null, firstFree)
        val nextStart = start + 100
        assertTrue(nextStart + (end - start) <= upper)
        val second = (nextStart..(nextStart + end - start)).first { EnginePortManager.isCandidateAvailable(it) { p -> p >= 3180 } }
        assertEquals(3180, second)
    }
}
