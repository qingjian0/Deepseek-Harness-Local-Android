package app.dsh.mobile.engine

import android.content.Context

/** One offset keeps every native listener in a collision-free, documented port block. */
object ServicePortConfig {
    data class Ports(val offset: Int, val engine: Int, val agentBridge: Int, val embedding: Int, val lanWeb: Int, val remoteAccess: Int)

    private const val PREFS = "service_port_config"
    private const val KEY_OFFSET = "offset"
    @Volatile private var activeOffset: Int = 0
    @Volatile private var initialized: Boolean = false

    private fun configuredOffset(context: Context): Int {
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_OFFSET, 0)
        return runCatching { portsForOffset(saved); saved }.getOrDefault(0)
    }

    /** Load the configured offset once for this process; later edits apply on engine restart. */
    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                activeOffset = configuredOffset(context)
                initialized = true
            }
        }
    }

    /** Activate the latest saved map when a new engine process is about to start. */
    fun activate(context: Context) {
        synchronized(this) {
            activeOffset = configuredOffset(context)
            initialized = true
        }
    }

    fun offset(context: Context): Int = configuredOffset(context)
    fun ports(context: Context): Ports = portsForOffset(configuredOffset(context))

    fun setOffset(context: Context, newOffset: Int) {
        portsForOffset(newOffset)
        val oldOffset = configuredOffset(context)
        if (newOffset == oldOffset) return
        val delta = newOffset - oldOffset
        EnginePortManager.shiftForOffset(context.applicationContext, delta)
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_OFFSET, newOffset).commit()
        if (!saved) {
            EnginePortManager.shiftForOffset(context.applicationContext, -delta)
            throw IllegalStateException("Could not save service port offset")
        }
    }

    val enginePort: Int get() = portsForOffset(activeOffset).engine
    val agentBridgePort: Int get() = portsForOffset(activeOffset).agentBridge
    val embeddingPort: Int get() = portsForOffset(activeOffset).embedding
    val lanWebPort: Int get() = portsForOffset(activeOffset).lanWeb
    val remoteAccessPort: Int get() = portsForOffset(activeOffset).remoteAccess

    fun reservedPorts(): Set<Int> {
        val p = portsForOffset(activeOffset)
        return setOf(p.agentBridge, p.embedding, p.lanWeb, p.remoteAccess)
    }

    internal fun reservedPortsForOffset(offset: Int): Set<Int> {
        val p = portsForOffset(offset)
        return setOf(p.agentBridge, p.embedding, p.lanWeb, p.remoteAccess)
    }

    internal fun portsForOffset(offset: Int): Ports {
        require(offset in -2000..57533) { "Offset must be between -2000 and 57533" }
        val p = Ports(offset, 3080 + offset, 3083 + offset, 3085 + offset, 3090 + offset, 3091 + offset)
        require(listOf(p.engine, p.agentBridge, p.embedding, p.lanWeb, p.remoteAccess).all { it in 1024..65535 })
        require(setOf(p.engine, p.agentBridge, p.embedding, p.lanWeb, p.remoteAccess).size == 5) { "Offset produces colliding service ports" }
        require(8000 + offset in 1024..65533) { "Offset moves the engine search limit outside the valid range" }
        return p
    }
}
