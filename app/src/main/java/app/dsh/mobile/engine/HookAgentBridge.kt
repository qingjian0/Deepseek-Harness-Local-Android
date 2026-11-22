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
    private const val METHOD_DISCOVERY_CANCEL = "host_discovery_cancel"
    private const val KEY_DISCOVERY_REQUEST = "discoveryRequest"
    private const val KEY_DISCOVERY_RESULT = "discoveryResult"
    private const val KEY_DISCOVERY_TASK = "discoveryTask"
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
        val discoveryTask = state.getString(KEY_DISCOVERY_TASK)?.takeIf { it.isNotBlank() }?.let(::JSONObject)
        JSONObject()
            .put("ok", true)
            .put("targetPackage", "com.openai.chatgpt")
            .put("providerReachable", true)
            .put("targetObserved", state.getLong(KEY_UPDATED, 0L) > 0L)
            .put("lastObservationAt", state.getLong(KEY_UPDATED, 0L))
            .put("enabled", state.getBoolean(KEY_ENABLED, true))
            .put("eventCount", report.optJSONArray("events")?.length() ?: 0)
            .put("configUpdatedAt", state.getLong(KEY_CONFIG_UPDATED, 0L))
            .put("discoveryStatus", discoveryTask?.optString("status") ?: "idle")
            .put("discoveryAttempt", discoveryTask?.optInt("attempt", 0) ?: 0)
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
        val className = request.optString("className").takeIf { it.isNotBlank() && it != "null" }
        val limit = request.optInt("limit", 100).coerceIn(1, 500)
        val cursor = request.optInt("cursor", 0).coerceAtLeast(0)
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
            .put("cursor", cursor)
        val queuedResult = call(context, METHOD_DISCOVER, Bundle().apply {
            putString(KEY_DISCOVERY_REQUEST, queued.toString())
        })
        if (!queuedResult.getBoolean(KEY_ACCEPTED, false)) {
            throw IllegalStateException("Module provider rejected the discovery request")
        }

        // The target process claims a lease and can retry transient failures. Do not return
        // a non-terminal attempt result: it may be queued again by the Provider.
        val deadline = SystemClock.elapsedRealtime() + 12_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = call(context, METHOD_DISCOVERY_RESULT, Bundle().apply {
                putString(KEY_DISCOVERY_ID, requestId)
            })
            val taskRaw = state.getString(KEY_DISCOVERY_TASK)
            if (!taskRaw.isNullOrBlank()) {
                val task = JSONObject(taskRaw)
                when (task.optString("status")) {
                    "SUCCEEDED" -> {
                        val envelope = state.getString(KEY_DISCOVERY_RESULT)
                            ?.takeIf { it.isNotBlank() }
                            ?.let(::JSONObject)
                            ?: throw IllegalStateException("Discovery completed without a result")
                        return@guarded (envelope.optJSONObject("result")
                            ?: throw IllegalStateException("Discovery result envelope is invalid"))
                            .put("requestId", requestId)
                            .toString()
                    }
                    "FAILED", "EXPIRED", "CANCELLED" -> {
                        val envelope = state.getString(KEY_DISCOVERY_RESULT)
                            ?.takeIf { it.isNotBlank() }
                            ?.let(::JSONObject)
                        throw IllegalStateException(
                            "Discovery ${task.optString("status").lowercase()}: " +
                                (task.optString("errorCode").takeIf { it.isNotBlank() && it != "null" }
                                    ?: envelope?.optJSONObject("result")?.optString("error")
                                    ?: "unknown_error")
                        )
                    }
                    // QUEUED/RUNNING are non-terminal; continue waiting through retries.
                }
            }
            SystemClock.sleep(150L)
        }
        throw java.util.concurrent.TimeoutException(
            "Target process did not finish discovery within 12 seconds; requestId=$requestId"
        )
    }

    fun discoverStatus(context: Context, query: String): Pair<Int, String> = guarded {
        val requestId = parseQuery(query)["requestId"].orEmpty()
        require(requestId.matches(Regex("[A-Fa-f0-9-]{16,64}"))) { "requestId is invalid" }
        val state = call(context, METHOD_DISCOVERY_RESULT, Bundle().apply {
            putString(KEY_DISCOVERY_ID, requestId)
        })
        val taskRaw = state.getString(KEY_DISCOVERY_TASK)
            ?: throw IllegalArgumentException("Discovery request was not found")
        val resultRaw = state.getString(KEY_DISCOVERY_RESULT)
        JSONObject()
            .put("ok", true)
            .put("task", JSONObject(taskRaw))
            .put("result", resultRaw?.takeIf { it.isNotBlank() }?.let(::JSONObject)?.opt("result") ?: JSONObject.NULL)
            .toString()
    }

    fun cancelDiscovery(context: Context, body: String): Pair<Int, String> = guarded {
        val requestId = JSONObject(body).optString("requestId")
        require(requestId.matches(Regex("[A-Fa-f0-9-]{16,64}"))) { "requestId is invalid" }
        val state = call(context, METHOD_DISCOVERY_CANCEL, Bundle().apply {
            putString(KEY_DISCOVERY_ID, requestId)
        })
        if (!state.getBoolean(KEY_ACCEPTED, false)) {
            throw IllegalStateException("Module Provider did not persist discovery cancellation")
        }
        JSONObject()
            .put("ok", true)
            .put("task", state.getString(KEY_DISCOVERY_TASK)?.let(::JSONObject) ?: JSONObject.NULL)
            .toString()
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
