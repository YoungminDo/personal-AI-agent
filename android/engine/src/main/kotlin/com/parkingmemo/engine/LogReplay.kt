package com.parkingmemo.engine

/**
 * Raw sensor log format written by the app (one event per line, monotonic ms):
 *
 *   P,t,hPa                 barometer
 *   S,t                     step detector
 *   H,t,bearingDeg          forward bearing
 *   G,t,lat,lon,accM,sats   GNSS fix
 *   E,t,NAME[,arg]          events: CAR_CONNECTED, CAR_DISCONNECTED, MANUAL_PARK, END_WALK, TICK
 *   R,t,r0..r8              rotation matrix (kept for offline heading research)
 *   A,t,ax,ay,az            linear acceleration (kept for offline research)
 *   # ...                   comments / header
 *
 * Replaying a log through [ParkingPipeline] reproduces the phone's result, so
 * thresholds can be tuned on real garage data without going back to the car.
 */
object LogFormat {
    fun pressure(t: Long, hPa: Double) = "P,$t,${"%.4f".format(java.util.Locale.US, hPa)}"
    fun step(t: Long) = "S,$t"
    fun heading(t: Long, deg: Double) = "H,$t,${"%.1f".format(java.util.Locale.US, deg)}"
    fun gnss(s: GnssSample) = "G,${s.tMs},${s.lat},${s.lon},${"%.1f".format(java.util.Locale.US, s.accuracyM)},${s.satellitesUsed}"
    fun event(t: Long, name: String, arg: String? = null) = if (arg == null) "E,$t,$name" else "E,$t,$name,$arg"
}

object LogReplay {
    data class Result(val outputs: List<ParkingPipeline.Output>, val records: List<ParkingRecord>)

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
                "H" -> pipeline.heading(t, f[2].toDouble())
                "G" -> pipeline.gnss(GnssSample(t, f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5].toInt()))
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
}
