package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServicePortConfigTest {
    @Test fun defaultPortMapMatchesRuntimeListeners() {
        val p = ServicePortConfig.portsForOffset(0)
        assertEquals(3080, p.engine)
        assertEquals(3083, p.agentBridge)
        assertEquals(3085, p.embedding)
        assertEquals(3090, p.lanWeb)
        assertEquals(3091, p.remoteAccess)
    }

    @Test fun offsetMovesEveryListenerTogetherWithoutCollisions() {
        val p = ServicePortConfig.portsForOffset(1000)
        assertEquals(4080, p.engine)
        assertEquals(4083, p.agentBridge)
        assertEquals(4085, p.embedding)
        assertEquals(4090, p.lanWeb)
        assertEquals(4091, p.remoteAccess)
        assertEquals(4, ServicePortConfig.reservedPortsForOffset(1000).size)
    }

    @Test fun minimumAndMaximumOffsetsRemainValid() {
        assertTrue(ServicePortConfig.portsForOffset(-2000).remoteAccess <= 65535)
        assertTrue(ServicePortConfig.portsForOffset(57533).remoteAccess <= 65535)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOffsetOutsideValidPortRange() { ServicePortConfig.portsForOffset(57534) }

    @Test fun defaultReservedPortsIncludeEmbedding() {
        assertFalse(EnginePortManager.isCandidateAvailable(3085) { true })
        assertTrue(EnginePortManager.isCandidateAvailable(3084) { true })
    }
}
