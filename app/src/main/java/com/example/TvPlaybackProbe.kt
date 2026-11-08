package com.example

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Diagnostic player telemetry and event tracer ported from Lumen-App.
 */
internal object TvPlaybackProbe {
    private const val PREFS = "tv_playback_probe"
    private const val REPORT = "report"
    private const val MAX_EVENTS = 80
    private var startedAt = 0L

    fun begin(context: Context, isLive: Boolean, mode: String) {
        startedAt = SystemClock.elapsedRealtime()
        val report = JSONObject()
            .put("report_id", UUID.randomUUID().toString().take(8))
            .put("live", isLive)
            .put("mode", mode.take(24))
            .put("events", JSONArray())
        save(context, report)
        event(context, "opened")
    }

    fun event(
        context: Context,
        name: String,
        detail: String? = null,
        value: Int? = null,
    ) {
        val report = load(context) ?: return
        val events = report.optJSONArray("events") ?: JSONArray()
        if (events.length() >= MAX_EVENTS) return
        val entry = JSONObject()
            .put("at_ms", (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L))
            .put("name", name.take(40))
        if (detail != null) entry.put("detail", detail.take(60))
        if (value != null) entry.put("value", value)
        events.put(entry)
        report.put("events", events)
        save(context, report)
    }

    private fun load(context: Context): JSONObject? = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(REPORT, null)?.let(::JSONObject)
    }.getOrNull()

    private fun save(context: Context, report: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(REPORT, report.toString()).apply()
    }
}
