package com.parkingmemo.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Planar position in metres. +x = east, +y = north. */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(k: Double) = Vec2(x * k, y * k)
    operator fun unaryMinus() = Vec2(-x, -y)
    fun norm(): Double = hypot(x, y)

    /** Rotates the vector so its bearing increases by [deg] (clockwise, compass sense). */
    fun rotatedBy(deg: Double): Vec2 {
        val r = Math.toRadians(deg)
        return Vec2(x * cos(r) + y * sin(r), -x * sin(r) + y * cos(r))
    }

    /** Compass bearing in degrees: 0 = north (+y), 90 = east (+x). */
    fun bearingDeg(): Double = normDeg(Math.toDegrees(atan2(x, y)))

    companion object {
        val ZERO = Vec2(0.0, 0.0)
        fun fromBearing(deg: Double, len: Double): Vec2 {
            val r = Math.toRadians(deg)
            return Vec2(len * sin(r), len * cos(r))
        }
    }
}

/** Normalises an angle to [0, 360). */
fun normDeg(d: Double): Double {
    var r = d % 360.0
    if (r < 0) r += 360.0
    return r
}

/** Smallest signed difference a - b, in (-180, 180]. */
fun angleDiff(a: Double, b: Double): Double {
    var d = normDeg(a - b)
    if (d > 180.0) d -= 360.0
    return d
}

fun median(xs: List<Double>): Double {
    require(xs.isNotEmpty()) { "median of empty list" }
    val s = xs.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}

/** Circular mean of angles in degrees, or null for an empty list. */
fun circularMeanDeg(angles: List<Double>): Double? {
    if (angles.isEmpty()) return null
    var c = 0.0
    var s = 0.0
    for (a in angles) {
        val r = Math.toRadians(a)
        c += cos(r)
        s += sin(r)
    }
    return normDeg(Math.toDegrees(atan2(s, c)))
}

/** Exponential moving average for angles (handles the 359 -> 0 wrap). */
class CircularEma(private val alpha: Double) {
    private var c = 0.0
    private var s = 0.0
    private var initialised = false

    fun push(deg: Double): Double {
        val r = Math.toRadians(deg)
        if (!initialised) {
            c = cos(r); s = sin(r); initialised = true
        } else {
            c += alpha * (cos(r) - c)
            s += alpha * (sin(r) - s)
        }
        return value!!
    }

    val value: Double?
        get() = if (initialised) normDeg(Math.toDegrees(atan2(s, c))) else null
}

/** Angle in degrees between two 3D vectors. */
fun angleBetweenDeg(a: DoubleArray, b: DoubleArray): Double {
    val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    val na = kotlin.math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
    val nb = kotlin.math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
    if (na < 1e-9 || nb < 1e-9) return 0.0
    return Math.toDegrees(kotlin.math.acos((dot / (na * nb)).coerceIn(-1.0, 1.0)))
}

/** A GNSS position used for open-air parking (F05). */
data class GeoFix(val lat: Double, val lon: Double, val accuracyM: Double) {
    /** Local east/north offset in metres from this fix to [to] (fine for a few km). */
    fun vectorTo(to: GeoFix): Vec2 {
        val dx = (to.lon - lon) * cos(Math.toRadians(lat)) * 111_320.0
        val dy = (to.lat - lat) * 110_540.0
        return Vec2(dx, dy)
    }
}

object Heading {
    /**
     * Walking-forward bearing for a hand-held phone.
     *
     * [r] is the 3x3 row-major rotation matrix (device -> world, world axes
     * east/north/up) as produced by Android's getRotationMatrixFromVector.
     * The device +y axis (top edge) points forward when the phone is held flat;
     * the device -z axis (camera side) points forward when it is held upright.
     * Adding both gives a stable forward direction across the tilts people use
     * while looking at the screen. Returns null when the phone is in a pose
     * where neither axis is horizontal enough to tell.
     */
    fun forwardBearing(r: DoubleArray): Double? {
        require(r.size >= 9)
        val east = r[1] - r[2]
        val north = r[4] - r[5]
        if (hypot(east, north) < 0.2) return null
        return normDeg(Math.toDegrees(atan2(east, north)))
    }
}
