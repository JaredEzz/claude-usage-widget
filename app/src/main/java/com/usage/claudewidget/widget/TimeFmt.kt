package com.usage.claudewidget.widget

object TimeFmt {
    const val FIVE_HOUR_MS = 5L * 60 * 60 * 1000
    const val SEVEN_DAY_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * How far into a rolling window we are, as a percent, given its fixed [windowMs] duration
     * and the [resetEpochMs] it next resets at. Lets you compare against [utilization] to see
     * whether usage is running ahead of or behind the pace of the window itself.
     */
    fun elapsedPct(resetEpochMs: Long, windowMs: Long, now: Long = System.currentTimeMillis()): Int {
        if (resetEpochMs <= 0L) return 0
        val remainingMs = (resetEpochMs - now).coerceIn(0, windowMs)
        val elapsedMs = windowMs - remainingMs
        return ((elapsedMs.toDouble() / windowMs) * 100).roundToIntSafe()
    }

    private fun Double.roundToIntSafe(): Int = Math.round(this).toInt().coerceIn(0, 100)

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

    /** Snapshot is considered stale once older than ~2 refresh cycles. */
    fun isStale(fetchedAt: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (fetchedAt <= 0L) return true
        return now - fetchedAt > 31 * 60 * 1000L // > 31 min
    }

    /** Wall-clock reset time in the device's local timezone, e.g. "5:27pm", or "" if unknown. */
    fun resetsAtClock(resetEpochMs: Long): String {
        if (resetEpochMs <= 0L) return ""
        val zdt = java.time.Instant.ofEpochMilli(resetEpochMs).atZone(java.time.ZoneId.systemDefault())
        val hour12 = if (zdt.hour % 12 == 0) 12 else zdt.hour % 12
        val ampm = if (zdt.hour < 12) "am" else "pm"
        return "%d:%02d%s".format(hour12, zdt.minute, ampm)
    }

    /** Combined "5:27pm · resets in 3h 52m" (or "reset at 5:27pm" once past), or "-" if unknown. */
    fun resetsSummary(resetEpochMs: Long, now: Long = System.currentTimeMillis()): String {
        val clock = resetsAtClock(resetEpochMs)
        if (clock.isEmpty()) return "-"
        val relative = resetsIn(resetEpochMs, now)
        return if (relative == "now") "reset at $clock" else "$clock · resets in $relative"
    }
}
