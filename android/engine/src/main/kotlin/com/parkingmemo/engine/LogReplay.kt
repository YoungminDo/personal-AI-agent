package com.parkingmemo.engine

import java.util.Locale

/**
 * Raw sensor log format written by the app (one event per line, monotonic ms):
 *
 *   P,t,hPa                    barometer
 *   S,t                        step
 *   H,t,bearingDeg[,ux,uy,uz]  forward bearing, optional gravity direction in device axes
 *   G,t,lat,lon,accM,sats      GNSS fix (sats -1 = not reported)
 *   E,t,NAME[,arg]             events: CAR_CONNECTED, CAR_DISCONNECTED, MANUAL_PARK, END_WALK,
 *                              GUIDE_START, GUIDE_REF, FOUND, ...
 *   R,t,r0..r8                 rotation matrix (kept for offline research)
 *   # ...                      comments / header
 *
 * Replaying a log through the engine reproduces what the phone computed, so
 * thresholds can be tuned on real garage data without going back to the car.
 */
object LogFormat {
    fun pressure(t: Long, hPa: Double) = "P,$t,${"%.4f".format(Locale.US, hPa)}"
    fun step(t: Long) = "S,$t"
    fun heading(t: Long, deg: Double, up: DoubleArray? = null): String =
        if (up == null) "H,$t,${"%.1f".format(Locale.US, deg)}"
        else "H,$t,${"%.1f".format(Locale.US, deg)},${"%.3f".format(Locale.US, up[0])},${"%.3f".format(Locale.US, up[1])},${"%.3f".format(Locale.US, up[2])}"
    fun gnss(s: GnssSample) = "G,${s.tMs},${s.lat},${s.lon},${"%.1f".format(Locale.US, s.accuracyM)},${s.satellitesUsed}"
    fun event(t: Long, name: String, arg: String? = null) = if (arg == null) "E,$t,$name" else "E,$t,$name,$arg"
}

object LogReplay {
    data class Result(val outputs: List<ParkingPipeline.Output>, val records: List<ParkingRecord>)

    private fun upOf(f: List<String>): DoubleArray? =
        if (f.size >= 6) doubleArrayOf(f[3].toDouble(), f[4].toDouble(), f[5].toDouble()) else null

    private fun gnssOf(t: Long, f: List<String>) =
        GnssSample(t, f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5].toInt())

    fun replayParking(lines: Sequence<String>, sites: SiteBook = SiteBook(), stepScale: Double = 1.0): Result {
        var lastT = 0L
        val pipeline = ParkingPipeline(sites, stepScale) { lastT }
        val outs = mutableListOf<ParkingPipeline.Output>()
        var nextTick = Long.MIN_VALUE
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val f = line.split(',')
            val t = f.getOrNull(1)?.toLongOrNull() ?: continue
            lastT = t
            if (nextTick == Long.MIN_VALUE) nextTick = t + 1_000
            while (t >= nextTick) {
                pipeline.tick(nextTick)?.let { outs += it }
                nextTick += 1_000
            }
            when (f[0]) {
                "P" -> pipeline.pressure(t, f[2].toDouble())
                "S" -> pipeline.step(t)?.let { outs += it }
                "H" -> pipeline.heading(t, f[2].toDouble(), upOf(f))
                "G" -> pipeline.gnss(gnssOf(t, f))
                "E" -> when (f[2]) {
                    "CAR_CONNECTED" -> pipeline.carConnected(t)?.let { outs += it }
                    "CAR_DISCONNECTED" -> pipeline.carDisconnected(t)?.let { outs += it }
                    "MANUAL_PARK" -> outs += pipeline.manualPark(t)
                    "END_WALK" -> pipeline.endWalkManually(t)?.let { outs += it }
                }
                else -> Unit
            }
        }
        return Result(outs, outs.filterIsInstance<ParkingPipeline.Output.Saved>().map { it.record })
    }

    /** Replays a guidance log against a record; returns one state per second of log time. */
    fun replayGuide(
        lines: Sequence<String>,
        rec: ParkingRecord,
        floorH: Double = FloorEstimator.DEFAULT_FLOOR_H,
        learned: Boolean = false,
    ): List<Pair<Long, GuideState>> {
        val s = GuideSession(rec, floorH, learned)
        val out = mutableListOf<Pair<Long, GuideState>>()
        var nextSample = Long.MIN_VALUE
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val f = line.split(',')
            val t = f.getOrNull(1)?.toLongOrNull() ?: continue
            when (f[0]) {
                "P" -> s.onPressure(t, f[2].toDouble())
                "S" -> s.onStep(t)
                "H" -> s.onHeading(t, f[2].toDouble(), upOf(f))
                "G" -> s.onGnss(gnssOf(t, f))
                else -> Unit
            }
            if (nextSample == Long.MIN_VALUE) nextSample = t
            if (t >= nextSample) {
                out += t to s.state(t)
                nextSample = t + 1_000
            }
        }
        return out
    }
}
