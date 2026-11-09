package com.parkingmemo.engine

import kotlin.math.pow

object Baro {
    /** International barometric formula; only differences between two readings are used. */
    fun altitudeM(hPa: Double): Double = 44330.0 * (1.0 - (hPa / 1013.25).pow(0.190295))

    /** Height of [toHpa] relative to [fromHpa] in metres (positive = higher). */
    fun dhM(fromHpa: Double, toHpa: Double): Double = altitudeM(toHpa) - altitudeM(fromHpa)
}

/**
 * Median-of-N followed by an EMA. Removes single-sample glitches and keeps the
 * 10 Hz barometer stream smooth enough for a vertical-speed estimate.
 */
class PressureSmoother(private val window: Int = 5, private val alpha: Double = 0.3) {
    private val buf = ArrayDeque<Double>()
    private var ema: Double? = null

    fun push(hPa: Double): Double {
        buf.addLast(hPa)
        if (buf.size > window) buf.removeFirst()
        val med = median(buf.toList())
        val prev = ema
        val next = if (prev == null) med else prev + alpha * (med - prev)
        ema = next
        return next
    }
}
