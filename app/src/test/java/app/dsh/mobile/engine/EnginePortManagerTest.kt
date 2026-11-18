package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnginePortManagerTest {
    @Test fun reservedInternalPortsAreSkipped() {
        assertFalse(EnginePortManager.isCandidateAvailable(3081) { it != 3083 })
        assertFalse(EnginePortManager.isCandidateAvailable(3083) { true })
        assertFalse(EnginePortManager.isCandidateAvailable(3085) { true })
        assertTrue(EnginePortManager.isCandidateAvailable(3084) { true })
    }

    @Test fun shizukuCompanionPortMustAlsoBeFree() {
        assertFalse(EnginePortManager.isCandidateAvailable(3080) { it != 3082 })
    }

    @Test fun occupiedFirstSegmentMovesToNextHundredBlock() {
        val found = EnginePortManager.findCandidate(3080, 3090, 8000) { port -> port >= 3180 }
        assertEquals(3180 to 3180, found)
    }
}
