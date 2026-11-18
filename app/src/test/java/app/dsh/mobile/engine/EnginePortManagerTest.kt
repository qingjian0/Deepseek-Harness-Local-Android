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

    @Test fun occupiedDefaultPortAutoIncrementsToNextUsablePort() {
        // 3081 is invalid because its companion 3083 is reserved; 3082 is next usable.
        val found = EnginePortManager.findCandidate(3080, 3090, 8000) { it != 3080 }
        assertEquals(3082 to 3080, found)
    }

    @Test fun searchDoesNotSkipFreePortsBetweenHundredPortBlocks() {
        // 3091 is reserved; the first valid candidate after this blocked range is 3092.
        val found = EnginePortManager.findCandidate(3080, 3090, 8000) { it >= 3092 }
        assertEquals(3092 to 3092, found)
    }

    @Test fun continuesSearchingWhenFirstHundredPortsAreOccupied() {
        val found = EnginePortManager.findCandidate(3080, 3090, 8000) { port -> port >= 3180 }
        assertEquals(3180 to 3180, found)
    }
}
