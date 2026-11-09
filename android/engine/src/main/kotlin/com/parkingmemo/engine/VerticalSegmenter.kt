package com.parkingmemo.engine

import kotlin.math.abs

enum class VerticalMode { ELEVATOR, ESCALATOR, STAIRS, RAMP }

data class VerticalSegment(
    val startMs: Long,
    /** Time the new plateau began (motion stopped). */
    val endMs: Long,
    /** Height change in metres, plateau to plateau (positive = up). */
    val dhM: Double,
    val mode: VerticalMode,
)

/**
 * Splits a smoothed height stream into flat plateaus and vertical moves.
 *
 * Heights are compared plateau-to-plateau (medians), never as absolute values,
 * so slow weather drift and short pressure spikes (fire doors, ventilation)
 * do not create fake floor changes. See scenarios B01-B04, B12, B13.
 */
class VerticalSegmenter(
    private val startSpeed: Double = 0.12,
    private val startOffset: Double = 0.6,
    private val stopSpeed: Double = 0.06,
    private val stopHoldMs: Long = 3_000,
    private val minDh: Double = 1.0,
    private val maxMoveMs: Long = 180_000,
) {
    private val hist = ArrayDeque<Pair<Long, Double>>()
    private val stepTimes = ArrayDeque<Long>()

    var moving = false
        private set
    private var moveStartMs = 0L
    private var preLevel = 0.0
    private var stillSinceMs = -1L

    /** Time of the last completed plateau start, or -1. */
    var lastPlateauStartMs = -1L
        private set

    val segments = mutableListOf<VerticalSegment>()

    fun onStep(tMs: Long) {
        stepTimes.addLast(tMs)
        while (stepTimes.isNotEmpty() && stepTimes.first() < tMs - maxMoveMs - 10_000) stepTimes.removeFirst()
    }

    /** Feeds one smoothed height sample. Returns a segment when a vertical move completes. */
    fun onHeight(tMs: Long, h: Double): VerticalSegment? {
        hist.addLast(tMs to h)
        while (hist.isNotEmpty() && hist.first().first < tMs - maxMoveMs - 15_000) hist.removeFirst()
        val v = slope(tMs - 2_000, tMs) ?: return null

        if (!moving) {
            val level = levelBetween(tMs - 8_000, tMs - 2_000) ?: return null
            if (abs(v) > startSpeed && abs(h - level) > startOffset) {
                moving = true
                moveStartMs = firstDepartureTime(level, tMs)
                preLevel = level
                stillSinceMs = -1L
            }
            return null
        }

        if (abs(v) < stopSpeed) {
            if (stillSinceMs < 0) stillSinceMs = tMs
        } else {
            stillSinceMs = -1L
        }
        val timedOut = tMs - moveStartMs > maxMoveMs
        if ((stillSinceMs >= 0 && tMs - stillSinceMs >= stopHoldMs) || timedOut) {
            val plateauStart = if (stillSinceMs >= 0) stillSinceMs else tMs - stopHoldMs
            val newLevel = levelBetween(plateauStart, tMs) ?: h
            moving = false
            lastPlateauStartMs = plateauStart
            val dh = newLevel - preLevel
            stillSinceMs = -1L
            if (abs(dh) < minDh) return null
            val durS = ((plateauStart - moveStartMs).coerceAtLeast(1_000)) / 1000.0
            val steps = stepTimes.count { it in moveStartMs..plateauStart }
            val seg = VerticalSegment(moveStartMs, plateauStart, dh, classify(abs(dh), durS, steps))
            segments += seg
            return seg
        }
        return null
    }

    /** True when not moving vertically and the current plateau has lasted [minMs]. */
    fun isSettled(tMs: Long, minMs: Long = 2_000): Boolean {
        if (moving) return false
        val oldest = hist.firstOrNull()?.first ?: return false
        return tMs - maxOf(lastPlateauStartMs, oldest) >= minMs
    }

    private fun levelBetween(t0: Long, t1: Long): Double? {
        val xs = hist.filter { it.first in t0..t1 }.map { it.second }
        return if (xs.size >= 3) median(xs) else null
    }

    /** Walks back to the first sample that left the plateau, for a better start time. */
    private fun firstDepartureTime(level: Double, now: Long): Long {
        var t = now
        for (i in hist.indices.reversed()) {
            val (ts, hv) = hist[i]
            if (abs(hv - level) < 0.25) break
            t = ts
        }
        return t
    }

    private fun slope(t0: Long, t1: Long): Double? {
        val pts = hist.filter { it.first in t0..t1 }
        if (pts.size < 3) return null
        val n = pts.size.toDouble()
        val mt = pts.sumOf { it.first.toDouble() } / n
        val mh = pts.sumOf { it.second } / n
        var num = 0.0
        var den = 0.0
        for ((t, hv) in pts) {
            val dt = (t - mt) / 1000.0
            num += dt * (hv - mh)
            den += dt * dt
        }
        return if (den <= 1e-9) null else num / den
    }

    companion object {
        /** Initial rules from the design doc; tune with field logs. */
        fun classify(absDh: Double, durS: Double, steps: Int): VerticalMode {
            val speed = absDh / durS
            val stepRate = steps / durS
            val dhPerStep = if (steps > 0) absDh / steps else Double.MAX_VALUE
            return when {
                stepRate < 0.5 && speed >= 0.5 -> VerticalMode.ELEVATOR
                stepRate < 0.5 && speed >= 0.12 -> VerticalMode.ESCALATOR
                stepRate >= 0.5 && dhPerStep >= 0.12 -> VerticalMode.STAIRS
                else -> VerticalMode.RAMP
            }
        }
    }
}
