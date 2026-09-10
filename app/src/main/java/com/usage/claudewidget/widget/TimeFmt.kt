package com.usage.claudewidget.widget

object TimeFmt {
    /** "resets in" value, e.g. "3h 12m", "5d 2h", "<1m", or "-" if unknown. */
    fun resetsIn(resetEpochMs: Long, now: Long = System.currentTimeMillis()): String {
        if (resetEpochMs <= 0L) return "-"
        var s = (resetEpochMs - now) / 1000
        if (s <= 0) return "now"
        val d = s / 86_400; s %= 86_400
        val h = s / 3_600; s %= 3_600
        val m = s / 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "<1m"
        }
    }

    /** Dashboard-style percent: one decimal below 10% ("0.9%"), whole above ("12%"). */
    fun pctText(pct: Float): String {
        val p = pct.coerceIn(0f, 100f)
        return if (p < 10f) {
            // One decimal, trimming ".0" so 5% doesn't read "5.0%".
            val tenths = (p * 10).toInt() / 10f
            if (tenths == tenths.toInt().toFloat()) "${tenths.toInt()}%" else "${"%.1f".format(tenths)}%"
        } else {
            "${p.toInt()}%"
        }
    }

    /** Long-form "resets in", e.g. "1 hour 42 minutes", "4 days 18 hours". */
    fun resetsInLong(resetEpochMs: Long, now: Long = System.currentTimeMillis()): String {
        if (resetEpochMs <= 0L) return "-"
        var s = (resetEpochMs - now) / 1000
        if (s <= 0) return "now"
        val d = s / 86_400; s %= 86_400
        val h = s / 3_600; s %= 3_600
        val m = s / 60
        return when {
            d > 0 -> "${d} ${if (d == 1L) "day" else "days"} ${h} ${if (h == 1L) "hour" else "hours"}"
            h > 0 -> "${h} ${if (h == 1L) "hour" else "hours"} ${m} ${if (m == 1L) "minute" else "minutes"}"
            m > 0 -> "${m} ${if (m == 1L) "minute" else "minutes"}"
            else -> "less than a minute"
        }
    }
    /** Snapshot is considered stale once older than ~2 refresh cycles. */
    fun isStale(fetchedAt: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (fetchedAt <= 0L) return true
        return now - fetchedAt > 31 * 60 * 1000L // > 31 min
    }
}
