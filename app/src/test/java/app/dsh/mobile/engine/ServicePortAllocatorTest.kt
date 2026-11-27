package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServicePortAllocatorTest {

    @Test
    fun defaultLayoutPreservesExistingServicePorts() {
        val ports = ServicePortMap.at(0)
        assertEquals(3080, ports.engine)
        assertEquals(3082, ports.shizuku)
        assertEquals(3083, ports.agentBridge)
        assertEquals(3085, ports.embedding)
        assertEquals(3090, ports.lanProxy)
        assertEquals(3091, ports.remoteAccess)
    }

    @Test
    fun shiftingMovesTheWholeBlockWithoutChangingRelativeOffsets() {
        val ports = ServicePortMap.at(2000)
        assertEquals(5080, ports.engine)
        assertEquals(5082, ports.shizuku)
        assertEquals(5083, ports.agentBridge)
        assertEquals(5085, ports.embedding)
        assertEquals(5090, ports.lanProxy)
        assertEquals(5091, ports.remoteAccess)
        assertEquals(2000, ports.offset)
    }

    @Test
    fun candidatesPreferPersistedOffsetThenTryOtherCompleteBlocks() {
        val candidates = ServicePortMap.candidates(preferredOffset = 2000, maxEnginePort = 8000)
        assertEquals(5, candidates.size)
        assertEquals(listOf(2000, 0, 1000, 3000, 4000), candidates.map { it.offset })
    }

    @Test
    fun allCandidatesStayWithinConfiguredEngineCeilingAndHaveUniquePorts() {
        val candidates = ServicePortMap.candidates(preferredOffset = 0, maxEnginePort = 8000)
        assertTrue(candidates.isNotEmpty())
        candidates.forEach { ports ->
            assertTrue(ports.engine <= 8000)
            assertTrue(ports.all.all { it in 1024..65535 })
            assertEquals(6, ports.all.distinct().size)
            assertFalse(ports.offset % 1000 != 0)
        }
    }

    @Test
    fun ceilingBelowFirstShiftDoesNotCreatePartialBlocks() {
        val candidates = ServicePortMap.candidates(preferredOffset = 1000, maxEnginePort = 4000)
        assertEquals(listOf(0, 1000), candidates.map { it.offset })
        assertTrue(candidates.all { it.remoteAccess <= 4011 })
    }
}
