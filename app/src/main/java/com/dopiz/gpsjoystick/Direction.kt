package com.dopiz.gpsjoystick

import kotlin.math.cos

/**
 * Cardinal move direction for the joystick test harness. [NONE] means "hold position".
 * [bearingDegrees] is the compass bearing reported on the injected Location.
 */
enum class Direction(val northSign: Int, val eastSign: Int, val bearingDegrees: Float) {
    NONE(0, 0, 0f),
    N(1, 0, 0f),
    E(0, 1, 90f),
    S(-1, 0, 180f),
    W(0, -1, 270f),
}

/**
 * Direction move engine — converts a heading + speed + elapsed time into a new lat/lng.
 * Lives with the service (not shared with GPX playback); only [SpeedModel] is shared.
 */
object DirectionEngine {
    private const val METERS_PER_DEGREE_LAT = 111_320.0

    /** Advance [lat]/[lng] one tick along [direction] at [speedMps] over [seconds]. */
    fun step(
        lat: Double,
        lng: Double,
        direction: Direction,
        speedMps: Double,
        seconds: Double,
    ): Pair<Double, Double> {
        if (direction == Direction.NONE || speedMps <= 0.0 || seconds <= 0.0) {
            return lat to lng
        }
        val distance = SpeedModel.distanceMeters(speedMps, seconds)
        val dLat = direction.northSign * distance / METERS_PER_DEGREE_LAT
        val metersPerDegreeLng = METERS_PER_DEGREE_LAT * cos(Math.toRadians(lat))
        val dLng = if (metersPerDegreeLng == 0.0) 0.0
            else direction.eastSign * distance / metersPerDegreeLng
        return (lat + dLat) to (lng + dLng)
    }
}
