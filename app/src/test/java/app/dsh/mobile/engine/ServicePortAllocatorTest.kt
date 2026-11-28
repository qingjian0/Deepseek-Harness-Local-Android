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
        assertEquals(listOf(2000, 3000, 4000, 0, 1000), candidates.map { it.offset })
    }

    @Test
    fun allCandidatesStayWithinConfiguredEngineCeilingAndHaveUniquePorts() {
        val candidates = ServicePortMap.candidates(preferredOffset = 0, maxEnginePort = 8000)
        assertTrue(candidates.isNotEmpty())
        candidates.forEach { ports ->
            assertTrue(ports.engine <= 8000)
            assertTrue(ports.all.all { it in 1024..65535 })
            assertEquals(6, ports.all.distinct().size)
            assertEquals(0, ports.offset % 1000)
        }
    }

    @Test
    fun constructorRejectsPortsThatDoNotMatchTheCanonicalBlock() {
        val error = runCatching {
            ServicePortMap(
                offset = 1000,
                engine = 4080,
                shizuku = 4082,
                agentBridge = 4083,
                embedding = 4085,
                lanProxy = 5090,
                remoteAccess = 5091,
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun engineCeilingSelectsOnlyCompleteBlocks() {
        val candidates = ServicePortMap.candidates(preferredOffset = 1000, maxEnginePort = 5000)
        assertEquals(listOf(4080, 3080), candidates.map { it.engine })
        assertEquals(listOf(4091, 3091), candidates.map { it.remoteAccess })
        assertTrue(candidates.all { it.engine <= 5000 })
    }

    @Test
    fun minimumEngineCeilingKeepsTheLegacyBlockOnly() {
        val candidates = ServicePortMap.candidates(preferredOffset = 9000, maxEnginePort = 3080)
        assertEquals(listOf(0), candidates.map { it.offset })
        assertEquals(listOf(3080), candidates.map { it.engine })
    }

    @Test
    fun wildcardProbeAcceptsAFreePort() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        assertTrue(ServicePortAllocator.canBind(port))
    }

    @Test
    fun wildcardProbeRejectsLoopbackListener() {
        java.net.ServerSocket().use { listener ->
            listener.reuseAddress = false
            listener.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 0))
            assertFalse(ServicePortAllocator.canBind(listener.localPort))
        }
    }

    @Test
    fun wildcardProbeRejectsWildcardListener() {
        java.net.ServerSocket().use { listener ->
            listener.reuseAddress = false
            listener.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("0.0.0.0"), 0))
            assertFalse(ServicePortAllocator.canBind(listener.localPort))
        }
    }

    @Test
    fun ceilingBelowFirstShiftDoesNotCreatePartialBlocks() {
        val candidates = ServicePortMap.candidates(preferredOffset = 1000, maxEnginePort = 5000)
        assertEquals(listOf(1000, 0), candidates.map { it.offset })
        assertEquals(listOf(4080, 3080), candidates.map { it.engine })
        assertEquals(listOf(4091, 3091), candidates.map { it.remoteAccess })
        assertTrue(candidates.all { it.engine <= 5000 })
    }
}
