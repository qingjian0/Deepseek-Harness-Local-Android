package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun oneOccupiedApplicationPortBlocksTheWholePortGroup() {
        val ports = ServicePortConfig.portsForOffset(0)
        val allAvailable = EnginePortManager.isPortBlockAvailable(ports, 3080) { true }
        val bridgeOccupied = EnginePortManager.isPortBlockAvailable(ports, 3080) { it != ports.agentBridge }
        val companionOccupied = EnginePortManager.isPortBlockAvailable(ports, 3080) { it != 3082 }
        assertTrue(allAvailable)
        assertFalse(bridgeOccupied)
        assertFalse(companionOccupied)
    }

    @Test fun portBlockMovesInExactThousandPortSteps() {
        assertEquals(1000, EnginePortManager.nextBlockOffset(0))
        assertEquals(2000, EnginePortManager.nextBlockOffset(1000))
        assertEquals(57533, EnginePortManager.nextBlockOffset(56533))
        assertNull(EnginePortManager.nextBlockOffset(57533))
    }
}
