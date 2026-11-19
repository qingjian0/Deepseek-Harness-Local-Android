package app.dsh.mobile.engine

/**
 * Distinguishes a required WebServer bind collision from unrelated plugin degradation.
 * Rotation is permitted only when all three independent signals agree.
 */
internal object EnginePortConflictDetector {
    fun shouldRotate(
        missingServices: List<String>,
        failedPluginIds: List<String>,
        logHasPortConflict: Boolean,
    ): Boolean {
        val webServerMissing = missingServices.any { it.equals("webServer", ignoreCase = true) }
        val webServerFailed = failedPluginIds.any { it.equals("webserver", ignoreCase = true) }
        return webServerMissing && webServerFailed && logHasPortConflict
    }
}
