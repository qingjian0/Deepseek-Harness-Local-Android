package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Immutable port assignment for every DSH-local HTTP service.
 *
 * The offsets deliberately preserve the existing layout. Moving a block changes
 * every endpoint together; a free WebUI port alone is not sufficient.
 */
data class ServicePortMap(
    val offset: Int,
    val engine: Int,
    val shizuku: Int,
    val agentBridge: Int,
    val embedding: Int,
    val lanProxy: Int,
    val remoteAccess: Int,
) {
    val all: List<Int>
        get() = listOf(engine, shizuku, agentBridge, embedding, lanProxy, remoteAccess)

    init {
        require(offset >= 0 && offset % BLOCK_STEP == 0) { "offset must be a non-negative 1000-port step" }
        require(
            engine == BASE_ENGINE + offset &&
                shizuku == BASE_ENGINE + offset + 2 &&
                agentBridge == BASE_ENGINE + offset + 3 &&
                embedding == BASE_ENGINE + offset + 5 &&
                lanProxy == BASE_ENGINE + offset + 10 &&
                remoteAccess == BASE_ENGINE + offset + 11,
        ) { "service ports must match the canonical block offsets" }
        require(all.all { it in 1024..65535 }) { "service port outside TCP range" }
        require(all.distinct().size == all.size) { "service ports must be unique" }
    }

    companion object {
        const val BASE_ENGINE = 3080
        const val BLOCK_STEP = 1000
        const val DEFAULT_MAX_ENGINE_PORT = 8000

        fun at(offset: Int): ServicePortMap = ServicePortMap(
            offset = offset,
            engine = BASE_ENGINE + offset,
            shizuku = BASE_ENGINE + offset + 2,
            agentBridge = BASE_ENGINE + offset + 3,
            embedding = BASE_ENGINE + offset + 5,
            lanProxy = BASE_ENGINE + offset + 10,
            remoteAccess = BASE_ENGINE + offset + 11,
        )

        /** Preferred offset first, then every remaining complete block in ascending order. */
        fun candidates(preferredOffset: Int, maxEnginePort: Int = DEFAULT_MAX_ENGINE_PORT): List<ServicePortMap> {
            val maxOffset = ((maxEnginePort.coerceIn(BASE_ENGINE, 65524) - BASE_ENGINE) / BLOCK_STEP) * BLOCK_STEP
            val offsets = (0..maxOffset step BLOCK_STEP).toList()
            val preferred = preferredOffset.takeIf { it in offsets }
            val remaining = if (preferred == null) {
                offsets
            } else {
                offsets.filter { it > preferred } + offsets.filter { it < preferred }
            }
            return (listOfNotNull(preferred) + remaining).map(::at)
        }
    }
}

/**
 * Selects one complete service block before any DSH listener is started.
 *
 * Availability probing binds wildcard IPv4 sockets so both loopback listeners
 * and 0.0.0.0 listeners are treated as collisions. Probes are closed immediately;
 * listeners still handle the small bind-race window and must report bind failures.
 */
object ServicePortAllocator {
    private const val TAG = "ServicePortAllocator"
    private const val PREFS = "dsh_service_ports"
    private const val KEY_OFFSET = "selected_offset"
    private const val KEY_MAX_ENGINE = "max_engine_port"

    @Volatile
    private var selected = false

    /** True only after AgentBridge/Remote Access preflight has bound the selected map. */
    @Volatile
    private var corePreflightReady = false

    @Volatile
    var current: ServicePortMap = ServicePortMap.at(0)
        private set

    @Synchronized
    @Throws(IOException::class)
    fun select(ctx: Context): ServicePortMap {
        if (selected) return current

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val maxEngine = prefs.getInt(KEY_MAX_ENGINE, ServicePortMap.DEFAULT_MAX_ENGINE_PORT)
        val preferred = prefs.getInt(KEY_OFFSET, 0)
        val candidates = ServicePortMap.candidates(preferred, maxEngine)
        val chosen = candidates.firstOrNull { map ->
            map.all.all(::canBind)
        } ?: throw IOException(
            "No complete DSH service-port block is available (checked ${candidates.size} blocks)",
        )

        current = chosen
        corePreflightReady = false
        selected = true
        prefs.edit().putInt(KEY_OFFSET, chosen.offset).apply()
        Log.i(TAG, "selected complete service block offset=${chosen.offset}: ${chosen.all}")
        return chosen
    }

    /**
     * Move to the next complete block after a listener bind race. This is safe only
     * during preflight, before the other DSH listeners have started.
     */
    @Synchronized
    fun advanceAfterBindFailure(ctx: Context, failedAgentPort: Int): ServicePortMap? {
        if (current.agentBridge != failedAgentPort) return null

        // A block migration is safe only before sidecar listeners have bound their
        // current ports. LAN proxy is deliberately detached from the engine lifecycle;
        // Remote Access and Shizuku can also outlive an engine restart. Never move the
        // process-wide map underneath one of these listeners.
        val activeSidecars = buildList {
            if (LanWebProxy.isRunning()) add("LAN proxy")
            if (RemoteAccessServer.isRunning()) add("Remote Access")
            if (ShizukuHttpBridge.isRunning()) add("Shizuku bridge")
            if (LocalEmbedder.isReady()) add("local embedding")
        }
        if (activeSidecars.isNotEmpty()) {
            Log.e(
                TAG,
                "refusing port-block migration after AgentBridge bind race: active sidecars=" +
                    activeSidecars.joinToString(),
            )
            return null
        }

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val maxEngine = prefs.getInt(KEY_MAX_ENGINE, ServicePortMap.DEFAULT_MAX_ENGINE_PORT)
        val next = ServicePortMap.candidates(current.offset, maxEngine)
            .asSequence()
            .filter { it.offset != current.offset }
            .firstOrNull { map -> map.all.all(::canBind) }
            ?: return null
        current = next
        corePreflightReady = false
        prefs.edit().putInt(KEY_OFFSET, next.offset).apply()
        Log.w(TAG, "bind race: advanced complete service block to offset=${next.offset}: ${next.all}")
        return next
    }

    /**
     * LAN proxy may start only after the selected map and required core listeners are ready.
     * This prevents EngineService/settings callbacks from binding the old default port block
     * while startup is still probing and potentially advancing the allocation.
     */
    fun isCorePreflightReady(): Boolean = selected && corePreflightReady

    @Synchronized
    fun markCorePreflightReady() {
        check(selected) { "service port block must be selected before core preflight completes" }
        corePreflightReady = true
        Log.i(TAG, "core service preflight complete; optional LAN proxy may start")
    }

    /** Test hook; production code should never reset the process-wide selection. */
    @Synchronized
    internal fun resetForTests() {
        selected = false
        corePreflightReady = false
        current = ServicePortMap.at(0)
    }

    internal fun canBind(port: Int): Boolean = runCatching {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
        }
        true
    }.getOrDefault(false)
}
