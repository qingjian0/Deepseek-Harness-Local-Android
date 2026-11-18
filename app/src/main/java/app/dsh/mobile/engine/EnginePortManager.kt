package app.dsh.mobile.engine

import android.content.Context
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Main engine port allocator with 100-port segment fallback. */
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
        val c = config(context)
        if (c.mode == Mode.EXACT) {
            if (!isCandidateAvailable(c.exact)) {
                throw IllegalStateException("Configured engine port ${c.exact} is occupied or reserved")
            }
            return Selection(c.exact, c.exact, c.exact, "exact")
        }
        val found = findCandidate(c.start, c.end, c.searchEnd, ::canBind)
            ?: throw IllegalStateException("No free engine port segment between ${c.start} and ${c.searchEnd}")
        val (port, segmentStart) = found
        val segmentEnd = segmentStart + (c.end - c.start)
        val reason = if (segmentStart == c.start) "preferred segment" else "fallback segment ${segmentStart}-${segmentEnd}"
        return Selection(port, segmentStart, segmentEnd, reason)
    }

    internal fun findCandidate(start: Int, end: Int, searchEnd: Int, available: (Int) -> Boolean): Pair<Int, Int>? {
        require(start in 1024..65533 && end in start..65533 && searchEnd >= end)
        val width = end - start
        var segmentStart = start
        while (segmentStart + width <= searchEnd) {
            val segmentEnd = segmentStart + width
            for (candidate in segmentStart..segmentEnd) {
                if (isCandidateAvailable(candidate, available)) return candidate to segmentStart
            }
            segmentStart += 100
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
