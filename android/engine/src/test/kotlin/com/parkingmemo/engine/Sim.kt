package com.parkingmemo.engine

import java.util.Random
import kotlin.math.cos
import kotlin.math.PI

/**
 * Deterministic synthetic sensor stream. Emits the same log lines the app
 * writes, so tests exercise the parser and the pipeline together.
 *
 * Barometer noise ~0.012 hPa (about 10 cm), heading noise ~5 deg,
 * steps every 550 ms (1.8 Hz cadence -> ~0.72 m per step in the model).
 */
class Sim(seed: Long = 7, var pHpa: Double = 1012.84, private val pressureOffset: Double = 0.0) {
    private val rnd = Random(seed)
    val lines = mutableListOf<String>()
    var t = 0L
        private set
    var gnssGood = true
    var heading = 0.0

    private fun noisyP() = pHpa + pressureOffset + rnd.nextGaussian() * 0.012
    private fun noisyH() = normDeg(heading + rnd.nextGaussian() * 5.0)

    private fun gnssLine() {
        val s = if (gnssGood) GnssSample(t, 37.5123, 127.0581, 6.0, 12) else GnssSample(t, 37.5121, 127.0580, 80.0, 1)
        lines += LogFormat.gnss(s)
    }

    /** Advances time, emitting pressure every 100 ms, heading every 100 ms, GNSS every 1 s. */
    fun stand(ms: Long, pFrom: Double = pHpa, pTo: Double = pHpa, stepEveryMs: Long? = null) {
        val start = t
        var nextStep = if (stepEveryMs != null) t + stepEveryMs else Long.MAX_VALUE
        while (t < start + ms) {
            t += 100
            val frac = (t - start).toDouble() / ms
            // Smooth S-curve, like a lift accelerating and braking.
            pHpa = pFrom + (pTo - pFrom) * (1 - cos(PI * frac.coerceIn(0.0, 1.0))) / 2
            lines += LogFormat.pressure(t, noisyP())
            lines += LogFormat.heading(t, noisyH())
            if (t % 1000 == 0L) gnssLine()
            if (t >= nextStep) {
                lines += LogFormat.step(t)
                nextStep += stepEveryMs!!
            }
        }
        pHpa = pTo
    }

    fun walk(meters: Double, bearing: Double) {
        heading = bearing
        val stepLen = Pdr.stepLength(1000.0 / 550)
        val n = Math.round(meters / stepLen)
        stand(n * 550, stepEveryMs = 550)
    }

    fun ride(pTo: Double, ms: Long) = stand(ms, pHpa, pTo)

    fun event(name: String) { lines += LogFormat.event(t, name) }
}
