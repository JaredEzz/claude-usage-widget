package com.usage.claudewidget.data

import org.json.JSONObject

/** One usage window (5-hour, weekly, monthly). */
data class Window(
    val utilization: Float,      // percent, 0..100
    val resetsAtEpochMs: Long,   // absolute reset time
)

/**
 * Snapshot of OpenCode Go usage returned by the official endpoint
 * GET https://opencode.ai/zen/go/v1/usage (Bearer auth with the opencode-go API key):
 *
 * ```
 * {"usage":{
 *   "rolling":{"status":"ok","percent":1,"resetsAt":"2026-09-09T07:30:37.834Z"},
 *   "weekly": {...},
 *   "monthly":{...}
 * }}
 * ```
 */
data class OpenCodeSnapshot(
    val rolling: Window,
    val weekly: Window,
    val monthly: Window,
    val fetchedAtEpochMs: Long,
) {
    companion object {
        fun parse(body: String, now: Long): OpenCodeSnapshot {
            val usage = JSONObject(body).optJSONObject("usage") ?: JSONObject()
            return OpenCodeSnapshot(
                rolling = window(usage, "rolling"),
                weekly = window(usage, "weekly"),
                monthly = window(usage, "monthly"),
                fetchedAtEpochMs = now,
            )
        }

        private fun window(usage: JSONObject, name: String): Window {
            val o = usage.optJSONObject(name) ?: JSONObject()
            val pct = o.optDouble("percent", 0.0).toFloat().coerceIn(0f, 100f)
            val reset = Iso8601.toEpochMs(o.optString("resetsAt", ""))
            return Window(pct, reset)
        }
    }
}
