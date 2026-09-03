package com.usage.claudewidget.data

import org.json.JSONArray
import org.json.JSONObject

/** One usage window (5-hour, 7-day/weekly, etc.). */
data class Window(
    val utilization: Float,      // percent, 0..100
    val resetsAtEpochMs: Long,   // absolute reset time
)

/**
 * Snapshot of Antigravity usage returned by Google Cloud Code API's
 * /v1internal:retrieveUserQuotaSummary endpoint (the same endpoint called by `agy usage`).
 */
data class AntigravitySnapshot(
    val gemini5h: Window,
    val geminiWeekly: Window,
    val claude5h: Window,
    val claudeWeekly: Window,
    val fetchedAtEpochMs: Long,
) {
    companion object {
        fun parseQuotaSummary(body: String, now: Long): AntigravitySnapshot {
            val root = JSONObject(body)
            val groups = root.optJSONArray("groups") ?: JSONArray()
            var gemini5h = Window(0f, 0L)
            var geminiWeekly = Window(0f, 0L)
            var claude5h = Window(0f, 0L)
            var claudeWeekly = Window(0f, 0L)

            for (i in 0 until groups.length()) {
                val group = groups.optJSONObject(i) ?: continue
                val displayName = group.optString("displayName", "")
                val isGemini = displayName.contains("Gemini", ignoreCase = true)
                val isClaude = displayName.contains("Claude", ignoreCase = true) ||
                    displayName.contains("GPT", ignoreCase = true) ||
                    displayName.contains("3p", ignoreCase = true)

                val buckets = group.optJSONArray("buckets") ?: JSONArray()
                for (j in 0 until buckets.length()) {
                    val bucket = buckets.optJSONObject(j) ?: continue
                    val windowType = bucket.optString("window", "")
                    val bucketId = bucket.optString("bucketId", "")
                    val remFrac = bucket.optDouble("remainingFraction", 1.0).toFloat()
                    val util = ((1.0f - remFrac) * 100f).coerceIn(0f, 100f)
                    val resetTime = bucket.optString("resetTime", "")
                    val resetEpochMs = if (resetTime.isNotBlank()) Iso8601.toEpochMs(resetTime) else 0L
                    val win = Window(util, resetEpochMs)

                    if (isGemini) {
                        if (windowType == "5h" || bucketId.contains("5h")) {
                            gemini5h = win
                        } else if (windowType == "weekly" || bucketId.contains("weekly")) {
                            geminiWeekly = win
                        }
                    } else if (isClaude) {
                        if (windowType == "5h" || bucketId.contains("5h")) {
                            claude5h = win
                        } else if (windowType == "weekly" || bucketId.contains("weekly")) {
                            claudeWeekly = win
                        }
                    }
                }
            }

            return AntigravitySnapshot(
                gemini5h = gemini5h,
                geminiWeekly = geminiWeekly,
                claude5h = claude5h,
                claudeWeekly = claudeWeekly,
                fetchedAtEpochMs = now,
            )
        }
    }
}

