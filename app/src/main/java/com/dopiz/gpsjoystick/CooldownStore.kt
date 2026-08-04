package com.dopiz.gpsjoystick

import android.content.Context

/**
 * Persists the cooldown that started at the last teleport, so every surface (map banner, home card,
 * foreground notification) can show the same countdown and it survives a process restart.
 * Deliberately tiny — two SharedPreferences keys, no scheduling, no listeners (YAGNI): callers poll
 * [remainingSeconds] on their own tick.
 */
object CooldownStore {

    private const val PREFS = "cooldown"
    private const val K_START_MS = "start_ms"
    private const val K_TOTAL_S = "total_s"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Record a cooldown of [totalSeconds] starting now. A later teleport simply overwrites this. */
    fun start(context: Context, totalSeconds: Int) {
        prefs(context).edit()
            .putLong(K_START_MS, System.currentTimeMillis())
            .putInt(K_TOTAL_S, totalSeconds)
            .apply()
    }

    /** @return seconds left, or 0 when there is no cooldown / it already elapsed. */
    fun remainingSeconds(context: Context): Long {
        val p = prefs(context)
        val startMs = p.getLong(K_START_MS, 0L)
        if (startMs == 0L) return 0L
        val total = p.getInt(K_TOTAL_S, 0)
        val elapsed = (System.currentTimeMillis() - startMs) / 1000
        // Clock changes can make elapsed negative; treat anything out of range as finished.
        if (elapsed < 0) return 0L
        return (total - elapsed).coerceAtLeast(0L)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(K_START_MS).remove(K_TOTAL_S).apply()
    }

    /**
     * Always two columns so every surface (banner, card, notification, overlay badge) stays the same
     * width: mm:ss below an hour, h:mm from an hour up (seconds stop mattering at that scale).
     */
    fun format(seconds: Long): String {
        val s = seconds.coerceAtLeast(0L)
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) "%d:%02d".format(h, m) else "%02d:%02d".format(m, s % 60)
    }
}
