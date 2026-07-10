package com.dopiz.gpsjoystick

/**
 * The single shared speed model (m/s) used across the app.
 *
 * Deliberately dependency-free and direction-agnostic: it only knows a speed value and how
 * far that speed travels in a given time. The joystick/direction engine AND the later GPX
 * playback engine both consume THIS — GPX shares only the speed model, not the direction
 * engine. Keep it that way so the two movement algorithms never fight over units.
 */
object SpeedModel {
    /** Range exposed by the speed slider, per spec (1–30 m/s). */
    const val MIN_MPS = 1.0
    const val MAX_MPS = 30.0

    fun clamp(mps: Double): Double = mps.coerceIn(MIN_MPS, MAX_MPS)

    /** Distance in meters covered at [speedMps] over [seconds]. */
    fun distanceMeters(speedMps: Double, seconds: Double): Double = speedMps * seconds
}
