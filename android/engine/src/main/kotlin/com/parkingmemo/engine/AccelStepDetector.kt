package com.parkingmemo.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Fallback step detector from the raw accelerometer, for phones without a
 * hardware step detector or when the activity permission is denied (G04).
 *
 * Peak picking on the band-limited magnitude of acceleration, with an adaptive
 * threshold and a minimum interval between steps. Rough, but good enough for
 * distance on a garage floor; the hardware detector is preferred when present.
 */
class AccelStepDetector(
    private val minIntervalMs: Long = 280,
    private val minThreshold: Double = 0.7,
) {
    private var lp: Double? = null
    private var baseline: Double? = null
    private var energy = 1.0
    private var prev = 0.0
    private var rising = false
    private var peakT = 0L
    private var peakV = 0.0
    private var lastStepMs = -10_000L

    /** Feeds one sample (m/s^2, any device axes, gravity included). Returns a step time or null. */
    fun onAccel(tMs: Long, ax: Double, ay: Double, az: Double): Long? {
        val mag = sqrt(ax * ax + ay * ay + az * az)
        val base = baseline?.let { it + 0.02 * (mag - it) } ?: mag
        baseline = base
        val dyn = mag - base
        val l = lp?.let { it + 0.35 * (dyn - it) } ?: dyn
        lp = l
        energy += 0.02 * (abs(l) - energy)
        val thr = max(minThreshold, 0.6 * energy)

        var step: Long? = null
        if (l > prev) {
            rising = true
            if (l > peakV) { peakV = l; peakT = tMs }
        } else if (rising && l < prev) {
            // Just passed a local maximum.
            rising = false
            if (peakV > thr && peakT - lastStepMs >= minIntervalMs) {
                lastStepMs = peakT
                step = peakT
            }
            peakV = 0.0
        }
        if (l < 0) peakV = 0.0
        prev = l
        return step
    }
}
