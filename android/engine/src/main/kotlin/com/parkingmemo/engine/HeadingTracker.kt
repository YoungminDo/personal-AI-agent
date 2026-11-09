package com.parkingmemo.engine

/**
 * Walking heading in a frame that stays consistent for one session, even when the
 * phone moves between hand, pocket and bag (D02-D05).
 *
 * The raw "forward bearing" of a pocketed phone is offset from the walking
 * direction by an unknown but roughly constant angle. What breaks a path is the
 * *change* of that offset when the phone is moved. A move is detected from the
 * gravity direction in device coordinates ([up]); stride-long averages hide the
 * normal leg swing. Across a move the walking direction is assumed continuous,
 * so the new offset is whatever keeps the heading where it was.
 *
 * The remaining constant offset (e.g. "pocketed from the start") is removed on
 * the return trip by [Guidance]'s door alignment.
 */
class HeadingTracker(
    private val windowMs: Long = 1_000,
    private val switchDeg: Double = 40.0,
    private val settleDeg: Double = 12.0,
    private val strideMs: Long = 1_200,
    private val maxSwitchMs: Long = 10_000,
) {
    private val raw = ArrayDeque<Pair<Long, Double>>()
    private val ups = ArrayDeque<Pair<Long, DoubleArray>>()
    private var switching = false
    private var frozen: Double? = null
    private var switchStartMs = 0L

    /** Subtracted from raw bearings to get the session frame. */
    var offsetDeg = 0.0
        private set
    var switches = 0
        private set
    val isSwitching: Boolean get() = switching

    fun onSample(tMs: Long, rawDeg: Double, up: DoubleArray? = null) {
        raw.addLast(tMs to rawDeg)
        while (raw.isNotEmpty() && raw.first().first < tMs - 6_000) raw.removeFirst()
        if (up == null) return
        ups.addLast(tMs to up)
        while (ups.isNotEmpty() && ups.first().first < tMs - 3 * strideMs) ups.removeFirst()

        val now = meanUp(tMs - strideMs, tMs) ?: return
        val before = meanUp(tMs - 2 * strideMs, tMs - strideMs) ?: return
        val change = angleBetweenDeg(now, before)

        if (!switching && change > switchDeg) {
            switching = true
            switchStartMs = tMs
            frozen = meanCorrected(tMs - 2 * strideMs - windowMs, tMs - 2 * strideMs)
                ?: meanCorrected(tMs - 2 * strideMs, tMs - strideMs)
            switches++
        } else if (switching && ((change < settleDeg && tMs - switchStartMs >= strideMs) || tMs - switchStartMs > maxSwitchMs)) {
            val newRaw = circularMeanDeg(raw.filter { it.first >= tMs - strideMs }.map { it.second })
            val f = frozen
            if (newRaw != null && f != null) offsetDeg = angleDiff(newRaw, f)
            switching = false
            frozen = null
        }
    }

    /** Walking heading in the session frame; frozen while the phone is being moved. */
    fun heading(tMs: Long): Double? {
        if (switching) frozen?.let { return it }
        return meanCorrected(tMs - windowMs, tMs) ?: raw.lastOrNull()?.let { normDeg(it.second - offsetDeg) }
    }

    /** Where the phone points right now, in the session frame (for drawing arrows). */
    fun pointing(tMs: Long): Double? =
        meanCorrected(tMs - 300, tMs) ?: raw.lastOrNull()?.let { normDeg(it.second - offsetDeg) }

    private fun meanCorrected(t0: Long, t1: Long): Double? =
        circularMeanDeg(raw.filter { it.first in t0..t1 }.map { normDeg(it.second - offsetDeg) })

    private fun meanUp(t0: Long, t1: Long): DoubleArray? {
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var n = 0
        for ((t, u) in ups) if (t in t0..t1) { x += u[0]; y += u[1]; z += u[2]; n++ }
        return if (n < 3) null else doubleArrayOf(x / n, y / n, z / n)
    }
}
