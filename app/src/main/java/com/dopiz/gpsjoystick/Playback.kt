package com.dopiz.gpsjoystick

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/** A route coordinate. Kept osmdroid-free so [MockState] stays dependency-light. */
data class GeoPt(val lat: Double, val lng: Double)

/** How playback behaves when it reaches the end of the route. */
enum class PlaybackMode { LOOP, REVERSE }

/**
 * In-memory GPX playback session. Held inside [MockState]; the service tick loop advances it
 * every tick via [PlaybackEngine.step]. Position is tracked as a cursor along the polyline
 * ([segmentIndex] + [segmentProgress] meters into the segment toward [forward] direction), so
 * pause simply freezes the cursor and resume continues from it.
 */
data class Playback(
    val points: List<GeoPt> = emptyList(),
    val mode: PlaybackMode = PlaybackMode.LOOP,
    val active: Boolean = false,
    val paused: Boolean = false,
    /** Index of the point the cursor last passed / sits on. */
    val segmentIndex: Int = 0,
    /** Meters travelled from [segmentIndex] toward the next point in the current direction. */
    val segmentProgress: Double = 0.0,
    /** REVERSE ping-pongs this; LOOP keeps it true. */
    val forward: Boolean = true,
) {
    val hasRoute: Boolean get() = points.size >= 2
}

/**
 * GPX playback engine — walks the polyline doing point-to-point TIME interpolation using the
 * shared [SpeedModel] speed. This is a DIFFERENT algorithm from [DirectionEngine]; only the
 * speed model is shared. Distances use a simple equirectangular approximation (meters), which
 * is plenty accurate at GPX-track scales — great-circle is intentionally not used (YAGNI).
 */
object PlaybackEngine {
    private const val M_PER_DEG_LAT = 111_320.0

    /** Straight-line meters between two coordinates (equirectangular approximation). */
    fun segMeters(a: GeoPt, b: GeoPt): Double {
        val dLatM = (b.lat - a.lat) * M_PER_DEG_LAT
        val meanLatRad = Math.toRadians((a.lat + b.lat) / 2.0)
        val dLngM = (b.lng - a.lng) * M_PER_DEG_LAT * cos(meanLatRad)
        return hypot(dLatM, dLngM)
    }

    /** Current interpolated position for a cursor state. */
    fun currentPos(pb: Playback): GeoPt {
        val pts = pb.points
        if (pts.isEmpty()) return GeoPt(MockState.DEFAULT_LAT, MockState.DEFAULT_LNG)
        val idx = pb.segmentIndex.coerceIn(0, pts.size - 1)
        val nextIdx = idx + if (pb.forward) 1 else -1
        if (nextIdx !in pts.indices) return pts[idx]
        val a = pts[idx]
        val b = pts[nextIdx]
        val len = segMeters(a, b)
        val f = if (len <= 0.0) 0.0 else (pb.segmentProgress / len).coerceIn(0.0, 1.0)
        return GeoPt(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
    }

    /** Compass bearing (0=N, 90=E) of the segment the cursor is currently traversing. */
    fun currentBearing(pb: Playback): Float {
        val pts = pb.points
        if (pts.size < 2) return 0f
        val idx = pb.segmentIndex.coerceIn(0, pts.size - 1)
        val nextIdx = idx + if (pb.forward) 1 else -1
        if (nextIdx !in pts.indices) return 0f
        val a = pts[idx]
        val b = pts[nextIdx]
        val north = (b.lat - a.lat) * M_PER_DEG_LAT
        val east = (b.lng - a.lng) * M_PER_DEG_LAT * cos(Math.toRadians((a.lat + b.lat) / 2.0))
        if (north == 0.0 && east == 0.0) return 0f
        return (((Math.toDegrees(atan2(east, north))) + 360.0) % 360.0).toFloat()
    }

    /**
     * Advance the cursor by [speedMps] over [seconds]. Returns the updated [Playback] cursor
     * plus the resulting interpolated position. Consumes the travel distance across as many
     * short segments as needed within one tick, handling loop/reverse at the route boundaries.
     */
    fun step(pb: Playback, speedMps: Double, seconds: Double): Pair<Playback, GeoPt> {
        val pts = pb.points
        if (!pb.hasRoute || speedMps <= 0.0 || seconds <= 0.0) {
            return pb to currentPos(pb)
        }
        var remaining = SpeedModel.distanceMeters(speedMps, seconds)
        var idx = pb.segmentIndex.coerceIn(0, pts.size - 1)
        var prog = pb.segmentProgress.coerceAtLeast(0.0)
        var forward = pb.forward

        // Guard against pathological all-zero-length routes spinning forever.
        var guard = pts.size * 4 + 16
        while (remaining > 0.0 && guard-- > 0) {
            val nextIdx = idx + if (forward) 1 else -1
            if (nextIdx !in pts.indices) {
                when (pb.mode) {
                    PlaybackMode.LOOP -> {
                        // End -> restart from the start of the route.
                        idx = 0
                        forward = true
                        prog = 0.0
                    }
                    PlaybackMode.REVERSE -> {
                        // End -> walk back the way we came.
                        forward = !forward
                        prog = 0.0
                    }
                }
                continue
            }
            val len = segMeters(pts[idx], pts[nextIdx])
            if (prog + remaining < len) {
                prog += remaining
                remaining = 0.0
            } else {
                remaining -= (len - prog)
                idx = nextIdx
                prog = 0.0
            }
        }
        val next = pb.copy(segmentIndex = idx, segmentProgress = prog, forward = forward)
        return next to currentPos(next)
    }
}
