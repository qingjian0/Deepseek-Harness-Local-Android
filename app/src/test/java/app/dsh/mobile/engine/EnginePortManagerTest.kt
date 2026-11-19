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
        assertTrue(allAvailable)

        // Every listener in the app's block is mandatory: a single collision
        // must reject the whole block so the allocator can advance by +1000.
        val listeners = setOf(
            ports.engine, ports.engine + 2, ports.agentBridge,
            ports.embedding, ports.lanWeb, ports.remoteAccess,
        )
        for (occupied in listeners) {
            assertFalse(
                "port $occupied must invalidate the complete application block",
                EnginePortManager.isPortBlockAvailable(ports, ports.engine) { it != occupied },
            )
        }
    }

    @Test fun portBlockMovesInExactThousandPortSteps() {
        assertEquals(1000, EnginePortManager.nextBlockOffset(0))
        assertEquals(2000, EnginePortManager.nextBlockOffset(1000))
        assertEquals(57533, EnginePortManager.nextBlockOffset(56533))
        assertNull(EnginePortManager.nextBlockOffset(57533))
    }

    @Test fun customEnginePortCannotAliasAnInternalServiceListener() {
        val ports = ServicePortConfig.portsForOffset(0)
        assertFalse(EnginePortManager.isPortBlockAvailable(ports, ports.agentBridge) { true })
        assertFalse(EnginePortManager.isPortBlockAvailable(ports, ports.embedding) { true })
        assertFalse(EnginePortManager.isPortBlockAvailable(ports, ports.lanWeb) { true })
        assertFalse(EnginePortManager.isPortBlockAvailable(ports, ports.remoteAccess) { true })
    }

    @Test fun customEngineCompanionCannotAliasAnInternalServiceListener() {
        val ports = ServicePortConfig.portsForOffset(0)
        assertFalse(EnginePortManager.isPortBlockAvailable(ports, ports.agentBridge - 2) { true })
        assertFalse(EnginePortManager.isPortBlockAvailable(ports, ports.embedding - 2) { true })
        assertFalse(EnginePortManager.isPortBlockAvailable(ports, ports.lanWeb - 2) { true })
        assertFalse(EnginePortManager.isPortBlockAvailable(ports, ports.remoteAccess - 2) { true })
    }

    @Test fun defaultEnginePairRemainsValidAsPartOfItsPortBlock() {
        val ports = ServicePortConfig.portsForOffset(0)
        assertTrue(EnginePortManager.isPortBlockAvailable(ports, ports.engine) { true })
    }
}
