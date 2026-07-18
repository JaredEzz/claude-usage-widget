package com.usage.claudewidget.data

import org.json.JSONObject

/** One usage window (5-hour or 7-day). */
data class Window(
    val utilization: Float,      // percent, 0..100
    val resetsAtEpochMs: Long,   // absolute reset time
)

/** A model-scoped weekly window, e.g. Fable's own weekly limit. [label] is the model's display name. */
data class ScopedWindow(
    val label: String,
    val window: Window,
)

/** Parsed snapshot of the /usage endpoint. */
data class UsageSnapshot(
    val fiveHour: Window,
    val sevenDay: Window,
    val scopedWeekly: ScopedWindow?,  // null when the account has no model-scoped weekly limit
    val fetchedAtEpochMs: Long,
) {
    companion object {
        fun parse(body: String, now: Long): UsageSnapshot {
            val root = JSONObject(body)
            return UsageSnapshot(
                fiveHour = root.getJSONObject("five_hour").toWindow(),
                sevenDay = root.getJSONObject("seven_day").toWindow(),
                scopedWeekly = root.scopedWeekly(),
                fetchedAtEpochMs = now,
            )
        }

        private fun JSONObject.toWindow(): Window {
            val util = optDouble("utilization", 0.0).toFloat()
            val reset = optString("resets_at", "")
            return Window(util, Iso8601.toEpochMs(reset))
        }

        /**
         * Pull the first model-scoped weekly limit out of the generic `limits[]` array.
         * The API exposes per-model caps (currently just Fable) as
         * `{"kind":"weekly_scoped","percent":9,"resets_at":...,"scope":{"model":{"display_name":"Fable"}}}`
         * rather than a dedicated top-level field, so we read it here.
         */
        private fun JSONObject.scopedWeekly(): ScopedWindow? {
            val limits = optJSONArray("limits") ?: return null
            for (i in 0 until limits.length()) {
                val entry = limits.optJSONObject(i) ?: continue
                if (entry.optString("kind") != "weekly_scoped") continue
                val label = entry.optJSONObject("scope")
                    ?.optJSONObject("model")
                    ?.optString("display_name")
                    ?.takeIf { it.isNotBlank() } ?: continue
                val window = Window(
                    entry.optDouble("percent", 0.0).toFloat(),
                    Iso8601.toEpochMs(entry.optString("resets_at", "")),
                )
                return ScopedWindow(label, window)
            }
            return null
        }
    }
}
