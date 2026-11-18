package app.dsh.mobile.engine

import android.content.Context
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Main engine port allocator with sequential fallback across the configured search range. */
object EnginePortManager {
    enum class Mode { AUTO, EXACT }
    data class Config(val mode: Mode, val start: Int, val end: Int, val searchEnd: Int, val exact: Int)
    data class Selection(val port: Int, val segmentStart: Int, val segmentEnd: Int, val reason: String)

    private const val PREFS = "engine_port_manager"

    fun config(context: Context): Config {
        val defaults = ServicePortConfig.ports(context)
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            mode = runCatching { Mode.valueOf(p.getString("mode", "AUTO") ?: "AUTO") }.getOrDefault(Mode.AUTO),
            start = p.getInt("start", defaults.engine),
            end = p.getInt("end", defaults.lanWeb),
            searchEnd = p.getInt("search_end", 8000 + defaults.offset),
            exact = p.getInt("exact", defaults.engine),
        )
    }

    fun setAuto(context: Context, start: Int, end: Int, searchEnd: Int) {
        require(start in 1024..65533 && end in start..65533 && searchEnd in end..65533)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", Mode.AUTO.name).putInt("start", start).putInt("end", end)
            .putInt("search_end", searchEnd).apply()
    }

    fun setExact(context: Context, port: Int) {
        require(port in 1024..65533)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", Mode.EXACT.name).putInt("exact", port).apply()
    }

    /** Shift saved engine ranges together with the service-port offset. */
    internal fun shiftForOffset(context: Context, delta: Int) {
        if (delta == 0) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = p.edit()
        for (key in listOf("start", "end", "search_end", "exact")) {
            if (!p.contains(key)) continue
            val shifted = p.getInt(key, 0) + delta
            require(shifted in 1024..65533) { "Port offset moves $key outside the valid range" }
            edit.putInt(key, shifted)
        }
        edit.remove("actual_port").remove("last_reason").remove("last_segment_start").remove("last_segment_end")
        check(edit.commit()) { "Could not shift saved engine port configuration" }
    }

    fun currentPort(context: Context): Int {
        ServicePortConfig.initialize(context)
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("actual_port", ServicePortConfig.enginePort)
    }

    fun remember(context: Context, selection: Selection) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("actual_port", selection.port)
            .putString("last_reason", selection.reason)
            .putInt("last_segment_start", selection.segmentStart)
            .putInt("last_segment_end", selection.segmentEnd)
            .apply()
    }

    fun lastReason(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("last_reason", "Not started") ?: "Not started"

    fun select(context: Context): Selection {
        ServicePortConfig.activate(context)
        val initialOffset = ServicePortConfig.offset(context)
        var offset = initialOffset
        var selectedPorts: ServicePortConfig.Ports? = null
        var selectedEnginePort: Int? = null

        // Treat every listener owned by this app as one port block. If ANY listener
        // (including the engine's +2 companion socket) is occupied, move the whole
        // block by exactly 1000 instead of drifting individual listeners.
        while (offset <= 57533) {
            val ports = runCatching { ServicePortConfig.portsForOffset(offset) }.getOrNull() ?: break
            val cfg = config(context)
            val enginePort = if (cfg.mode == Mode.EXACT) cfg.exact else cfg.start
            val required = buildSet {
                add(ports.engine)
                add(ports.engine + 2)
                add(ports.agentBridge)
                add(ports.embedding)
                add(ports.lanWeb)
                add(ports.remoteAccess)
                add(enginePort)
                add(enginePort + 2)
            }
            if (required.all(::canBind)) {
                selectedPorts = ports
                selectedEnginePort = enginePort
                break
            }

            offset += 1000
            ServicePortConfig.setOffset(context, offset)
            ServicePortConfig.activate(context)
        }

        val ports = selectedPorts
            ?: throw IllegalStateException("No free application port block; tried offsets from $initialOffset in steps of 1000")
        val port = selectedEnginePort!!
        val cfg = config(context)
        if (cfg.mode == Mode.EXACT) {
            return Selection(port, port, port, "exact; application port block offset +${ports.offset}")
        }
        return Selection(port, port, port, "application port block offset +${ports.offset}")
    }

    internal fun findCandidate(start: Int, end: Int, searchEnd: Int, available: (Int) -> Boolean): Pair<Int, Int>? {
        require(start in 1024..65533 && end in start..65533 && searchEnd >= end)
        // Scan every candidate in order. The previous 100-port stride only tested
        // [start..end], [start+100..end+100], ... and silently skipped free ports
        // in between (e.g. 3091..3179). That could report exhaustion while usable
        // ports were available. Keep the preferred range first, then increment by 1.
        for (candidate in start..searchEnd) {
            if (isCandidateAvailable(candidate, available)) {
                val rangeStart = if (candidate <= end) start else candidate
                return candidate to rangeStart
            }
        }
        return null
    }

    internal fun isCandidateAvailable(port: Int, available: (Int) -> Boolean = ::canBind): Boolean {
        val companion = port + 2
        if (port !in 1024..65533 || companion > 65535) return false
        val reserved = ServicePortConfig.reservedPorts()
        if (port in reserved || companion in reserved) return false
        return available(port) && available(companion)
    }

    private fun canBind(port: Int): Boolean = runCatching {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(port))
        }
        true
    }.getOrDefault(false)
}
