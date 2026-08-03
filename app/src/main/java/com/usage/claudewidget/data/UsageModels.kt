package com.usage.claudewidget.data

import org.json.JSONObject

/** One usage window (5-hour, 7-day, or AGY quota). */
data class Window(
    val utilization: Float,      // percent, 0..100
    val resetsAtEpochMs: Long,   // absolute reset time
)

/** A model-scoped window, e.g. Fable or AGY Sonnet. [label] is the model's display name. */
data class ScopedWindow(
    val label: String,
    val window: Window,
)

/** Parsed snapshot of Claude + Antigravity (AGY) usage endpoints. */
data class UsageSnapshot(
    val fiveHour: Window,
    val sevenDay: Window,
    val scopedWeekly: ScopedWindow?,
    val agyQuota: Window?,
    val agyScoped: ScopedWindow?,
    val fetchedAtEpochMs: Long,
) {
    companion object {
        fun parseClaude(body: String, now: Long, agyQuota: Window? = null, agyScoped: ScopedWindow? = null): UsageSnapshot {
            val root = JSONObject(body)
            return UsageSnapshot(
                fiveHour = root.getJSONObject("five_hour").toWindow(),
                sevenDay = root.getJSONObject("seven_day").toWindow(),
                scopedWeekly = root.scopedWeekly(),
                agyQuota = agyQuota,
                agyScoped = agyScoped,
                fetchedAtEpochMs = now,
            )
        }

        fun parseAgy(body: String, now: Long): Pair<Window?, ScopedWindow?> {
            return try {
                val root = JSONObject(body)
                val models = root.optJSONArray("models") ?: return Pair(null, null)
                var mainQuota: Window? = null
                var scopedQuota: ScopedWindow? = null

                for (i in 0 until models.length()) {
                    val m = models.optJSONObject(i) ?: continue
                    if (m.optBoolean("isAutocompleteOnly", false)) continue
                    val remPct = m.optDouble("remainingPercentage", 1.0).toFloat()
                    val util = ((1.0f - remPct) * 100f).coerceIn(0f, 100f)
                    val resetTimeStr = m.optString("resetTime", "")
                    val resetMs = Iso8601.toEpochMs(resetTimeStr)
                    val label = m.optString("label", "AGY").ifBlank { "AGY" }
                    val modelId = m.optString("modelId", "")

                    val win = Window(utilization = util, resetsAtEpochMs = resetMs)
                    if (mainQuota == null || modelId.contains("flash", ignoreCase = true)) {
                        mainQuota = win
                    } else if (scopedQuota == null) {
                        scopedQuota = ScopedWindow(label = label.take(12), window = win)
                    }
                }
                Pair(mainQuota, scopedQuota)
            } catch (_: Exception) {
                Pair(null, null)
            }
        }

        private fun JSONObject.toWindow(): Window {
            val util = optDouble("utilization", 0.0).toFloat()
            val reset = optString("resets_at", "")
            return Window(util, Iso8601.toEpochMs(reset))
        }

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

