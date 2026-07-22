package com.dopiz.gpsjoystick

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Community-observed Pokemon GO cooldown guidance. This is not an official Niantic API or rule.
 * Distances between listed thresholds are rounded up to the next (safer) wait bracket.
 */
object PokemonGoCooldown {

    data class Estimate(val distanceMeters: Double, val waitSeconds: Int)

    private data class Bracket(val maxKm: Double, val waitSeconds: Int)

    private val brackets = listOf(
        Bracket(1.0, 30),
        Bracket(5.0, 2 * 60),
        Bracket(10.0, 6 * 60),
        Bracket(25.0, 11 * 60),
        Bracket(30.0, 14 * 60),
        Bracket(65.0, 22 * 60),
        Bracket(81.0, 25 * 60),
        Bracket(100.0, 35 * 60),
        Bracket(250.0, 45 * 60),
        Bracket(500.0, 60 * 60),
        Bracket(750.0, 80 * 60),
        Bracket(1_000.0, 90 * 60),
        Bracket(1_500.0, 120 * 60),
    )

    fun estimate(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Estimate {
        val distanceMeters = greatCircleMeters(fromLat, fromLng, toLat, toLng)
        if (distanceMeters < NEGLIGIBLE_DISTANCE_METERS) return Estimate(distanceMeters, 0)
        val distanceKm = distanceMeters / 1_000.0
        val wait = brackets.firstOrNull { distanceKm <= it.maxKm }?.waitSeconds
            ?: MAX_WAIT_SECONDS
        return Estimate(distanceMeters, wait)
    }

    private fun greatCircleMeters(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double,
    ): Double {
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val deltaLat = Math.toRadians(toLat - fromLat)
        val deltaLng = Math.toRadians(toLng - fromLng)
        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLng / 2) * sin(deltaLng / 2)
        return EARTH_RADIUS_METERS * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val NEGLIGIBLE_DISTANCE_METERS = 10.0
    private const val MAX_WAIT_SECONDS = 120 * 60
}
