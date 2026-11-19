package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/** One persisted port plan for every app-owned local service. */
data class EnginePortPlan(
    val web: Int,
    val companion: Int,
    val bridge: Int,
    val embedding: Int,
    val lanWeb: Int,
    val remoteAccess: Int,
) {
    val allPorts: List<Int>
        get() = listOf(web, companion, bridge, embedding, lanWeb, remoteAccess)

    companion object {
        const val DEFAULT_BASE = 3080
        const val GROUP_STEP = 1000

        fun fromBase(base: Int): EnginePortPlan {
            require(base in 1024..(65535 - 11)) { "invalid base port: $base" }
            return EnginePortPlan(
                web = base,
                companion = base + 2,
                bridge = base + 3,
                embedding = base + 5,
                lanWeb = base + 10,
                remoteAccess = base + 11,
            )
        }
    }
}

/**
 * Selects a complete group before app-owned listeners start. Probes every port
 * simultaneously and never kills an unknown process. A small bind race remains
 * after releasing the probe sockets; real listeners must still handle bind errors.
 */
object EnginePortManager {
    private const val TAG = "EnginePortManager"
    private const val PREFS = "dsh_engine_ports"
    private const val KEY_BASE = "base_port"
    private val lock = Any()
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @Volatile private var loaded = false
    @Volatile private var selected = false
    @Volatile private var active = EnginePortPlan.fromBase(EnginePortPlan.DEFAULT_BASE)

    fun current(): EnginePortPlan = active

    fun load(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_BASE, EnginePortPlan.DEFAULT_BASE)
            val base = saved.takeIf { it in 1024..(65535 - 11) } ?: EnginePortPlan.DEFAULT_BASE
            active = EnginePortPlan.fromBase(base)
            loaded = true
            Log.i(TAG, "loaded port group: ${active.allPorts.joinToString()}")
        }
    }

    fun select(context: Context): EnginePortPlan {
        load(context)
        if (selected) return active
        synchronized(lock) {
            if (selected) return active
            var candidate = active.web
            var attempts = 0
            while (candidate <= 65535 - 11 && attempts < 64) {
                val plan = EnginePortPlan.fromBase(candidate)
                if (groupAvailable(plan)) {
                    active = plan
                    val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putInt(KEY_BASE, candidate).commit()
                    if (!saved) throw IOException("cannot persist selected port group")
                    selected = true
                    Log.i(TAG, "selected port group: ${plan.allPorts.joinToString()}")
                    return plan
                }
                Log.w(TAG, "port group busy: ${plan.allPorts.joinToString()}; trying +${EnginePortPlan.GROUP_STEP}")
                candidate += EnginePortPlan.GROUP_STEP
                attempts++
            }
            throw IOException("no free DSH port group found after $attempts attempts")
        }
    }

    private fun groupAvailable(plan: EnginePortPlan): Boolean {
        val held = ArrayList<ServerSocket>(plan.allPorts.size)
        return try {
            for (port in plan.allPorts) {
                val socket = ServerSocket()
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(loopback, port), 1)
                held += socket
            }
            true
        } catch (e: Exception) {
            Log.d(TAG, "port group probe failed: ${e.message}")
            false
        } finally {
            held.forEach { runCatching { it.close() } }
        }
    }
}
