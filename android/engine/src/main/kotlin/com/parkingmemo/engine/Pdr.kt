package com.parkingmemo.engine

import kotlin.math.abs
import kotlin.math.roundToLong

data class TrackPoint(val tMs: Long, val pos: Vec2, val headingDeg: Double, val lenM: Double)

/**
 * Pedestrian dead reckoning: step events x step length, along the current
 * smoothed heading. Optionally snaps headings to the garage grid (C01, C04).
 */
class Pdr(
    private val stepScale: Double = 1.0,
    private val gridDeg: Double? = null,
    private val snapTolDeg: Double = 15.0,
    /** Shared with the caller when it also needs the arrow direction. */
    val tracker: HeadingTracker = HeadingTracker(),
    private val ownsTracker: Boolean = true,
) {
    private var lastStepMs = -1L
    private var lastHeading = 0.0

    var pos: Vec2 = Vec2.ZERO
        private set
    var distanceM = 0.0
        private set
    val track = mutableListOf<TrackPoint>()

    /** Feeds the heading tracker (ignored when the tracker is fed by the owner). */
    fun onHeading(tMs: Long, deg: Double, up: DoubleArray? = null) {
        if (ownsTracker) tracker.onSample(tMs, deg, up)
    }

    fun onStep(tMs: Long): Vec2 {
        val dt = if (lastStepMs >= 0) tMs - lastStepMs else 550L
        lastStepMs = tMs
        val freq = if (dt in 250L..1500L) 1000.0 / dt else 1.8
        val len = stepLength(freq) * stepScale
        var h = tracker.heading(tMs) ?: lastHeading
        lastHeading = h
        if (gridDeg != null) h = snap(h, gridDeg, snapTolDeg)
        pos += Vec2.fromBearing(h, len)
        distanceM += len
        track += TrackPoint(tMs, pos, h, len)
        return pos
    }

    companion object {
        /** Linear step-length model from cadence; per-user scale is learned later. */
        fun stepLength(freqHz: Double): Double = (0.26 * freqHz + 0.25).coerceIn(0.45, 0.95)

        fun snap(h: Double, grid: Double, tol: Double): Double {
            val k = (angleDiff(h, grid) / 90.0).roundToLong()
            val target = normDeg(grid + 90.0 * k)
            return if (abs(angleDiff(h, target)) <= tol) target else h
        }
    }
}

object PathTools {
    /** Grid orientation (0..90) from the first straight stretch of at least [minLenM]. */
    fun gridAngle(track: List<TrackPoint>, minLenM: Double = 5.0, tolDeg: Double = 20.0): Double? {
        var i = 0
        while (i < track.size) {
            val ref = track[i].headingDeg
            var j = i
            var len = 0.0
            val hs = mutableListOf<Double>()
            while (j < track.size && abs(angleDiff(track[j].headingDeg, ref)) <= tolDeg) {
                len += track[j].lenM
                hs += track[j].headingDeg
                j++
            }
            if (len >= minLenM) {
                val mean = circularMeanDeg(hs) ?: return null
                return mean % 90.0
            }
            i = maxOf(j, i + 1)
        }
        return null
    }

    /** Recomputes positions from step lengths and (optionally grid-snapped) headings. */
    fun rebuild(track: List<TrackPoint>, gridDeg: Double?, tolDeg: Double = 15.0): List<Vec2> {
        var p = Vec2.ZERO
        val out = ArrayList<Vec2>(track.size)
        for (tp in track) {
            val h = if (gridDeg != null) Pdr.snap(tp.headingDeg, gridDeg, tolDeg) else tp.headingDeg
            p += Vec2.fromBearing(h, tp.lenM)
            out += p
        }
        return out
    }

    /**
     * Index of the last point (up to [maxIdx]) still within [radiusM] of the car.
     * Wandering around the car (boot, back seat) before leaving is dropped (C08).
     */
    fun foldStart(points: List<Vec2>, maxIdx: Int, radiusM: Double = 3.0): Int {
        var last = -1
        for (i in 0..minOf(maxIdx, points.lastIndex)) if (points[i].norm() < radiusM) last = i
        return last
    }

    /** Douglas-Peucker simplification. */
    fun simplify(points: List<Vec2>, epsM: Double = 1.5): List<Vec2> {
        if (points.size < 3) return points
        var maxD = 0.0
        var idx = 0
        val a = points.first()
        val b = points.last()
        for (i in 1 until points.lastIndex) {
            val d = distToSegment(points[i], a, b)
            if (d > maxD) { maxD = d; idx = i }
        }
        return if (maxD > epsM) {
            val left = simplify(points.subList(0, idx + 1), epsM)
            val right = simplify(points.subList(idx, points.size), epsM)
            left.dropLast(1) + right
        } else listOf(a, b)
    }

    private fun distToSegment(p: Vec2, a: Vec2, b: Vec2): Double {
        val ab = b - a
        val len2 = ab.x * ab.x + ab.y * ab.y
        if (len2 < 1e-9) return (p - a).norm()
        val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0.0, 1.0)
        return (p - (a + ab * t)).norm()
    }
}
