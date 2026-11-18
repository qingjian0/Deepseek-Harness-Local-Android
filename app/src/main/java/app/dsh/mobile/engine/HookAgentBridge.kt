package app.dsh.mobile.engine

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * DSH host adapter for the dsh-gpt module provider.
 *
 * The loopback HTTP layer authenticates the Agent with DSH_BRIDGE_TOKEN before these
 * methods are called. The provider separately verifies that the Binder caller is the
 * installed DSH host package; the bridge token is never sent to the target process.
 */
object HookAgentBridge {
    private const val AUTHORITY = "com.gpt.hook.observations"
    private const val URI = "content://$AUTHORITY"
    private const val METHOD_SNAPSHOT = "host_snapshot"
    private const val METHOD_SET_CONFIG = "host_set_config"
    private const val METHOD_SET_ENABLED = "host_set_enabled"
    private const val METHOD_DISCOVER = "host_discover"
    private const val METHOD_DISCOVERY_RESULT = "host_discovery_result"
    private const val KEY_DISCOVERY_REQUEST = "discoveryRequest"
    private const val KEY_DISCOVERY_RESULT = "discoveryResult"
    private const val KEY_DISCOVERY_ID = "discoveryId"
    private const val KEY_REPORT = "report"
    private const val KEY_CONFIG = "config"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ACCEPTED = "accepted"
    private const val KEY_UPDATED = "updatedAt"
    private const val KEY_CONFIG_UPDATED = "configUpdatedAt"

    fun status(context: Context): Pair<Int, String> = guarded {
        val state = snapshot(context)
        val report = parseReport(state.getString(KEY_REPORT))
        JSONObject()
            .put("ok", true)
            .put("targetPackage", "com.openai.chatgpt")
            .put("providerReachable", true)
            .put("targetObserved", state.getLong(KEY_UPDATED, 0L) > 0L)
            .put("lastObservationAt", state.getLong(KEY_UPDATED, 0L))
            .put("enabled", state.getBoolean(KEY_ENABLED, true))
            .put("eventCount", report.optJSONArray("events")?.length() ?: 0)
            .put("configUpdatedAt", state.getLong(KEY_CONFIG_UPDATED, 0L))
            .toString()
    }

    fun events(context: Context, query: String): Pair<Int, String> = guarded {
        val params = parseQuery(query)
        val since = params["since"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
        val report = parseReport(snapshot(context).getString(KEY_REPORT))
        val source = report.optJSONArray("events") ?: JSONArray()
        val result = JSONArray()
        for (i in 0 until source.length()) {
            val event = source.optJSONObject(i) ?: continue
            if (event.optLong("sequence", 0L) <= since) continue
            result.put(event)
            if (result.length() >= limit) break
        }
        JSONObject()
            .put("ok", true)
            .put("schemaVersion", report.optInt("schemaVersion", 1))
            .put("kind", "dsh-hook-observations")
            .put("events", result)
            .put("nextSince", if (result.length() == 0) since else result.getJSONObject(result.length() - 1).optLong("sequence"))
            .toString()
    }

    fun rules(context: Context): Pair<Int, String> = guarded {
        val state = snapshot(context)
        val config = state.getString(KEY_CONFIG).orEmpty()
        JSONObject(config.ifBlank {
            """{"schemaVersion":1,"targetPackage":"com.openai.chatgpt","rules":[]}"""
        }).put("ok", true).toString()
    }

    fun updateRules(context: Context, body: String): Pair<Int, String> = guarded {
        val config = JSONObject(body)
        val extras = Bundle().apply { putString(KEY_CONFIG, config.toString()) }
        val result = call(context, METHOD_SET_CONFIG, extras)
        if (!result.getBoolean(KEY_ACCEPTED, false)) {
            throw IllegalStateException("Module provider did not persist the Hook configuration")
        }
        JSONObject()
            .put("ok", true)
            .put("accepted", true)
            .put("configUpdatedAt", result.getLong(KEY_CONFIG_UPDATED, 0L))
            .put("message", "Configuration stored; target process applies it on its next provider poll")
            .toString()
    }

    fun control(context: Context, body: String): Pair<Int, String> = guarded {
        val request = JSONObject(body)
        if (!request.has(KEY_ENABLED)) throw IllegalArgumentException("enabled is required")
        val extras = Bundle().apply { putBoolean(KEY_ENABLED, request.getBoolean(KEY_ENABLED)) }
        val result = call(context, METHOD_SET_ENABLED, extras)
        if (!result.getBoolean(KEY_ACCEPTED, false)) {
            throw IllegalStateException("Module provider did not persist the Hook control state")
        }
        JSONObject()
            .put("ok", true)
            .put("enabled", request.getBoolean(KEY_ENABLED))
            .put("message", "Control state stored; target process applies it on its next provider poll")
            .toString()
    }

    fun discover(context: Context, body: String): Pair<Int, String> = guarded {
        val request = JSONObject(body)
        val prefix = request.optString("prefix", "com.openai.")
        val className = request.optString("className").takeIf { it.isNotBlank() }
        val limit = request.optInt("limit", 100).coerceIn(1, 500)
        require(prefix.startsWith("com.openai.") && prefix.length <= 180) {
            "prefix must be within com.openai.*"
        }
        if (className != null) require(className.startsWith("com.openai.") && className.length <= 240) {
            "className must be within com.openai.*"
        }

        val requestId = java.util.UUID.randomUUID().toString()
        val queued = JSONObject()
            .put("requestId", requestId)
            .put("prefix", prefix)
            .put("className", className ?: JSONObject.NULL)
            .put("limit", limit)
        val queuedResult = call(context, METHOD_DISCOVER, Bundle().apply {
            putString(KEY_DISCOVERY_REQUEST, queued.toString())
        })
        if (!queuedResult.getBoolean(KEY_ACCEPTED, false)) {
            throw IllegalStateException("Module provider rejected the discovery request")
        }

        // The target process polls independently; stay within the Agent plugin's 15-second client timeout.
        val deadline = SystemClock.elapsedRealtime() + 12_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val result = call(context, METHOD_DISCOVERY_RESULT, Bundle().apply {
                putString(KEY_DISCOVERY_ID, requestId)
            }).getString(KEY_DISCOVERY_RESULT)
            if (!result.isNullOrBlank()) return@guarded result
            SystemClock.sleep(150L)
        }
        throw java.util.concurrent.TimeoutException(
            "Target process did not complete discovery within 12 seconds; retry the same query"
        )
    }

    private fun snapshot(context: Context): Bundle =
        call(context, METHOD_SNAPSHOT, null)

    private fun call(context: Context, method: String, extras: Bundle?): Bundle =
        context.contentResolver.call(Uri.parse(URI), method, null, extras)
            ?: throw IllegalStateException("dsh-gpt provider returned no response")

    private fun parseReport(raw: String?): JSONObject {
        val report = raw?.takeIf { it.isNotBlank() }?.let(::JSONObject)
            ?: JSONObject("""{"schemaVersion":1,"kind":"dsh-hook-observations","events":[]}""")
        if (report.optJSONArray("events") == null) report.put("events", JSONArray())
        return report
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split("&").filter { it.contains("=") }.associate {
            val index = it.indexOf("=")
            URLDecoder.decode(it.substring(0, index), "UTF-8") to
                URLDecoder.decode(it.substring(index + 1), "UTF-8")
        }

    private inline fun guarded(block: () -> String): Pair<Int, String> = try {
        200 to block()
    } catch (t: java.util.concurrent.TimeoutException) {
        504 to JSONObject().put("ok", false).put("error", "target_process_timeout").put("message", t.message).toString()
    } catch (t: SecurityException) {
        403 to JSONObject().put("ok", false).put("error", "provider_denied").put("message", t.message).toString()
    } catch (t: IllegalArgumentException) {
        400 to JSONObject().put("ok", false).put("error", "invalid_request").put("message", t.message).toString()
    } catch (t: Throwable) {
        502 to JSONObject().put("ok", false).put("error", "hook_provider_error").put("message", t.message ?: t.javaClass.simpleName).toString()
    }
}
