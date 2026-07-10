package com.dopiz.gpsjoystick

import android.content.Context

/**
 * Lightweight SharedPreferences persistence of the running session, so the foreground service
 * can restore the last mock position + playback after a real process kill (plan Slice 3's
 * "restart and continue" acceptance). Deliberately minimal — no DB, no serialization lib:
 * the route polyline is stored as a compact "lat,lng;lat,lng" string (YAGNI).
 */
object SessionStore {

    private const val PREFS = "mock_session"
    private const val K_RUNNING = "running"
    private const val K_LAT = "lat"
    private const val K_LNG = "lng"
    private const val K_SPEED = "speed"
    private const val K_PB_ACTIVE = "pb_active"
    private const val K_PB_PAUSED = "pb_paused"
    private const val K_PB_MODE = "pb_mode"
    private const val K_PB_INDEX = "pb_index"
    private const val K_PB_PROGRESS = "pb_progress"
    private const val K_PB_FORWARD = "pb_forward"
    private const val K_PB_ROUTE = "pb_route"

    fun save(context: Context, s: MockState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putBoolean(K_RUNNING, s.isRunning)
            putLong(K_LAT, s.latitude.toRawBits())
            putLong(K_LNG, s.longitude.toRawBits())
            putLong(K_SPEED, s.speedMps.toRawBits())
            putBoolean(K_PB_ACTIVE, s.playback.active)
            putBoolean(K_PB_PAUSED, s.playback.paused)
            putString(K_PB_MODE, s.playback.mode.name)
            putInt(K_PB_INDEX, s.playback.segmentIndex)
            putLong(K_PB_PROGRESS, s.playback.segmentProgress.toRawBits())
            putBoolean(K_PB_FORWARD, s.playback.forward)
            putString(K_PB_ROUTE, encodeRoute(s.playback.points))
            apply()
        }
    }

    /** @return the persisted session, or null if nothing was ever saved. */
    fun load(context: Context): MockState? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains(K_LAT)) return null
        val route = decodeRoute(p.getString(K_PB_ROUTE, "") ?: "")
        val mode = runCatching {
            PlaybackMode.valueOf(p.getString(K_PB_MODE, PlaybackMode.LOOP.name)!!)
        }.getOrDefault(PlaybackMode.LOOP)
        return MockState(
            isRunning = p.getBoolean(K_RUNNING, false),
            latitude = Double.fromBits(p.getLong(K_LAT, MockState.DEFAULT_LAT.toRawBits())),
            longitude = Double.fromBits(p.getLong(K_LNG, MockState.DEFAULT_LNG.toRawBits())),
            speedMps = Double.fromBits(p.getLong(K_SPEED, MockState.DEFAULT_SPEED_MPS.toRawBits())),
            playback = Playback(
                points = route,
                mode = mode,
                active = p.getBoolean(K_PB_ACTIVE, false),
                paused = p.getBoolean(K_PB_PAUSED, false),
                segmentIndex = p.getInt(K_PB_INDEX, 0),
                segmentProgress = Double.fromBits(p.getLong(K_PB_PROGRESS, 0L)),
                forward = p.getBoolean(K_PB_FORWARD, true),
            ),
        )
    }

    // --- Feature 1: last-selected speed preset chip (index; -1 = none) ---
    private const val PREFS_UI = "ui_prefs"
    private const val K_SPEED_CHIP = "speed_chip"

    fun saveSpeedChip(context: Context, index: Int) {
        context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .edit().putInt(K_SPEED_CHIP, index).apply()
    }

    fun loadSpeedChip(context: Context): Int =
        context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE).getInt(K_SPEED_CHIP, -1)

    // --- Feature 6: persisted overlay settings (lock + window positions + joystick visibility) ---
    private const val K_LOCK = "joy_lock"
    private const val K_JOY_VISIBLE = "joy_visible"
    private const val K_HUB_X = "hub_x"
    private const val K_HUB_Y = "hub_y"
    private const val K_JOY_X = "joy_x"
    private const val K_JOY_Y = "joy_y"
    private const val UNSET = Int.MIN_VALUE

    private fun ui(context: Context) =
        context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)

    fun saveLock(context: Context, locked: Boolean) {
        ui(context).edit().putBoolean(K_LOCK, locked).apply()
    }

    fun loadLock(context: Context): Boolean = ui(context).getBoolean(K_LOCK, false)

    fun saveJoystickVisible(context: Context, visible: Boolean) {
        ui(context).edit().putBoolean(K_JOY_VISIBLE, visible).apply()
    }

    fun loadJoystickVisible(context: Context): Boolean =
        ui(context).getBoolean(K_JOY_VISIBLE, false)

    /** Persist the hub CENTRE position (screen px). */
    fun saveHubPos(context: Context, cx: Int, cy: Int) {
        ui(context).edit().putInt(K_HUB_X, cx).putInt(K_HUB_Y, cy).apply()
    }

    /** @return (centreX, centreY) in screen px, or null if never saved. */
    fun loadHubPos(context: Context): Pair<Int, Int>? {
        val p = ui(context)
        val x = p.getInt(K_HUB_X, UNSET)
        val y = p.getInt(K_HUB_Y, UNSET)
        return if (x == UNSET || y == UNSET) null else x to y
    }

    /** Persist the joystick window TOP-LEFT position (screen px). */
    fun saveJoystickPos(context: Context, x: Int, y: Int) {
        ui(context).edit().putInt(K_JOY_X, x).putInt(K_JOY_Y, y).apply()
    }

    /** @return (x, y) top-left in screen px, or null if never saved. */
    fun loadJoystickPos(context: Context): Pair<Int, Int>? {
        val p = ui(context)
        val x = p.getInt(K_JOY_X, UNSET)
        val y = p.getInt(K_JOY_Y, UNSET)
        return if (x == UNSET || y == UNSET) null else x to y
    }

    private fun encodeRoute(points: List<GeoPt>): String =
        points.joinToString(";") { "${it.lat},${it.lng}" }

    private fun decodeRoute(raw: String): List<GeoPt> {
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { pair ->
            val parts = pair.split(",")
            val lat = parts.getOrNull(0)?.toDoubleOrNull()
            val lng = parts.getOrNull(1)?.toDoubleOrNull()
            if (lat != null && lng != null) GeoPt(lat, lng) else null
        }
    }
}
