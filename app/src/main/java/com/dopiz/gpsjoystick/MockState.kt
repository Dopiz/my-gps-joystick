package com.dopiz.gpsjoystick

/**
 * In-memory state owned by [MockLocationService] and exposed via StateFlow.
 * No DB, no DI — this is the single source of truth for the running mock session.
 */
data class MockState(
    val isRunning: Boolean = false,
    val latitude: Double = DEFAULT_LAT,
    val longitude: Double = DEFAULT_LNG,
    val speedMps: Double = DEFAULT_SPEED_MPS,
    val direction: Direction = Direction.NONE,
    /**
     * Free-heading joystick vector. When [headingActive] the move engine follows
     * [headingNorth]/[headingEast] instead of the cardinal [direction] enum.
     */
    val headingActive: Boolean = false,
    val headingNorth: Double = 0.0,
    val headingEast: Double = 0.0,
    /**
     * Global movement freeze driven by the overlay hub's ▶/⏸ button. When true the tick loop
     * holds the last fix and advances NOTHING — locked hands-free march, smooth glide, AND GPX
     * playback all freeze — but keeps re-injecting the held position so the provider stays alive.
     * Master gate over all position advancement; transient (not persisted).
     */
    val movementPaused: Boolean = false,
    /** Straight-line walk-to-coordinate target. Transient; arrival clears [walkToActive]. */
    val walkToActive: Boolean = false,
    val walkFromLat: Double = 0.0,
    val walkFromLng: Double = 0.0,
    val walkToLat: Double = 0.0,
    val walkToLng: Double = 0.0,
    /** GPX playback session (route + cursor + mode). Empty route = nothing to play. */
    val playback: Playback = Playback(),
    /**
     * Feature 4: smooth-glide teleport. When [glideActive] the tick loop eases the injected
     * position from ([glideFromLat],[glideFromLng]) to ([glideToLat],[glideToLng]) over
     * [glideDurationMs], starting at [glideStartMs] (SystemClock.elapsedRealtime). Transient —
     * not persisted.
     */
    val glideActive: Boolean = false,
    val glideFromLat: Double = 0.0,
    val glideFromLng: Double = 0.0,
    val glideToLat: Double = 0.0,
    val glideToLng: Double = 0.0,
    val glideStartMs: Long = 0L,
    val glideDurationMs: Long = 0L,
    /** Non-null when the last command failed (e.g. app not selected as mock app). */
    val error: String? = null,
) {
    companion object {
        // Taipei 101, a convenient default.
        const val DEFAULT_LAT = 25.0339
        const val DEFAULT_LNG = 121.5645
        const val DEFAULT_SPEED_MPS = 5.0
    }
}
