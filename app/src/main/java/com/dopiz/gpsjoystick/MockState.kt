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
