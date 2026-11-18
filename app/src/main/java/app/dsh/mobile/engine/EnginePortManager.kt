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
    private const val DEFAULT_START = 3080
    private const val DEFAULT_END = 3090
    private const val DEFAULT_SEARCH_END = 8000
    private const val DEFAULT_EXACT = 3080
    private val reserved = setOf(3083, 3090, 3091)

    fun config(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            mode = runCatching { Mode.valueOf(p.getString("mode", "AUTO") ?: "AUTO") }.getOrDefault(Mode.AUTO),
            start = p.getInt("start", DEFAULT_START),
            end = p.getInt("end", DEFAULT_END),
            searchEnd = p.getInt("search_end", DEFAULT_SEARCH_END),
            exact = p.getInt("exact", DEFAULT_EXACT),
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

    fun currentPort(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("actual_port", EngineConfig.DEFAULT_PORT)

    fun remember(context: Context, selection: Selection) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("actual_port", selection.port)
            .putString("last_reason", selection.reason)
            .putInt("last_segment_start", selection.segmentStart)
            .putInt("last_segment_end", selection.segmentEnd)
            .apply()
    }

    fun lastReason(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("last_reason", "尚未启动") ?: "尚未启动"

    fun select(context: Context): Selection {
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
