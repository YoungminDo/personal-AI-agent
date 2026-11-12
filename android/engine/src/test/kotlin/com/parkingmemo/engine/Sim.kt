package com.parkingmemo.engine

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class Carry { HAND, POCKET }

/**
 * Deterministic synthetic sensor stream. Emits the same log lines the app
 * writes, so tests exercise the parser and the engine together.
 *
 * - Barometer noise ~0.012 hPa (about 10 cm).
 * - Heading = true walking bearing + carry offset + ~5 deg noise. A pocketed
 *   phone's gravity vector swings with the leg (+-20 deg at stride frequency).
 * - Steps every 550 ms (1.8 Hz -> ~0.72 m per step in the model).
 * - GNSS follows the simulated position when "good".
 */
class Sim(seed: Long = 7, var pHpa: Double = 1012.84, private val pressureOffset: Double = 0.0) {
    private val rnd = Random(seed)
    val lines = mutableListOf<String>()
    var t = 0L
        private set
    var gnssGood = true
    var gnssAccuracy = 5.0
    var heading = 0.0
    var carry = Carry.HAND
    var carryOffset = 0.0
    var pos = Vec2.ZERO

    private val baseLat = 37.5123
    private val baseLon = 127.0581

    private fun noisyP() = pHpa + pressureOffset + rnd.nextGaussian() * 0.012
    private fun rawHeading() = normDeg(heading + carryOffset + rnd.nextGaussian() * 5.0)

    private fun up(): DoubleArray = when (carry) {
        Carry.HAND -> doubleArrayOf(rnd.nextGaussian() * 0.02, 0.70, 0.71)
        Carry.POCKET -> {
            val th = Math.toRadians(20.0 * sin(2 * PI * 0.9 * t / 1000.0))
            doubleArrayOf(rnd.nextGaussian() * 0.02, cos(th), sin(th))
        }
    }

    private fun gnssLine() {
        val s = if (gnssGood) {
            val lat = baseLat + pos.y / 110_540.0
            val lon = baseLon + pos.x / (111_320.0 * cos(Math.toRadians(baseLat)))
            GnssSample(t, lat, lon, gnssAccuracy, 12)
        } else {
            GnssSample(t, baseLat, baseLon, 80.0, 1)
        }
        lines += LogFormat.gnss(s)
    }

    /** Advances time: pressure and heading every 100 ms, GNSS every 1 s, optional steps. */
    fun stand(ms: Long, pFrom: Double = pHpa, pTo: Double = pHpa, stepEveryMs: Long? = null, stepLen: Double = 0.0) {
        val start = t
        var nextStep = if (stepEveryMs != null) t + stepEveryMs else Long.MAX_VALUE
        while (t < start + ms) {
            t += 100
            val frac = (t - start).toDouble() / ms
            // Smooth S-curve, like a lift accelerating and braking.
            pHpa = pFrom + (pTo - pFrom) * (1 - cos(PI * frac.coerceIn(0.0, 1.0))) / 2
            lines += LogFormat.pressure(t, noisyP())
            lines += LogFormat.heading(t, rawHeading(), up())
            if (t % 1000 == 0L) gnssLine()
            if (t >= nextStep) {
                lines += LogFormat.step(t)
                pos += Vec2.fromBearing(heading, stepLen)
                nextStep += stepEveryMs!!
            }
        }
        pHpa = pTo
    }

    /** Walks [meters] on [bearing]; optionally changing pressure (ramps). */
    fun walk(meters: Double, bearing: Double, pTo: Double = pHpa) {
        heading = bearing
        val stepLen = Pdr.stepLength(1000.0 / 550)
        val n = Math.round(meters / stepLen)
        stand(n * 550, pHpa, pTo, stepEveryMs = 550, stepLen = stepLen)
    }

    fun ride(pTo: Double, ms: Long) = stand(ms, pHpa, pTo)

    fun event(name: String) { lines += LogFormat.event(t, name) }
}

/** Feeds log lines into a guidance session and samples the state once per second. */
class SessionFeeder(val session: GuideSession) {
    val seen = mutableListOf<GuideState>()
    private var fed = 0

    fun feed(sim: Sim) {
        for (line in sim.lines.subList(fed, sim.lines.size)) {
            val f = line.split(',')
            val t = f[1].toLong()
            when (f[0]) {
                "P" -> session.onPressure(t, f[2].toDouble())
                "S" -> session.onStep(t)
                "H" -> session.onHeading(t, f[2].toDouble(),
                    if (f.size >= 6) doubleArrayOf(f[3].toDouble(), f[4].toDouble(), f[5].toDouble()) else null)
                "G" -> session.onGnss(GnssSample(t, f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5].toInt()))
            }
            if (f[0] == "P" && t % 1000 == 0L) seen += session.state(t)
        }
        fed = sim.lines.size
    }
}
