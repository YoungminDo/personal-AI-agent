package com.parkingmemo.engine

data class PressureSample(val tMs: Long, val hPa: Double)
data class HeadingSample(val tMs: Long, val deg: Double)

data class GnssSample(
    val tMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val satellitesUsed: Int,
) {
    /**
     * Quality gate (C11, C13): only fixes passing it are trusted as "outdoors".
     * [satellitesUsed] < 0 means the phone did not report satellite status;
     * then a tighter accuracy limit is used instead.
     */
    val good: Boolean
        get() = if (satellitesUsed >= 0) satellitesUsed >= 4 && accuracyM <= 25.0 else accuracyM <= 15.0
}

/** Rolling record of the last few minutes of sensor data, used to rewind (P4). */
class SensorBuffer(private val keepMs: Long = 10 * 60_000L) {
    val pressure = ArrayDeque<PressureSample>()
    val steps = ArrayDeque<Long>()
    val headings = ArrayDeque<HeadingSample>()
    val gnss = ArrayDeque<GnssSample>()

    fun addPressure(tMs: Long, hPa: Double) { pressure.addLast(PressureSample(tMs, hPa)); trim(tMs) }
    fun addStep(tMs: Long) { steps.addLast(tMs); trim(tMs) }
    fun addHeading(tMs: Long, deg: Double) { headings.addLast(HeadingSample(tMs, deg)); trim(tMs) }
    fun addGnss(s: GnssSample) { gnss.addLast(s); trim(s.tMs) }

    fun pressureMedian(t0: Long, t1: Long): Double? {
        val xs = pressure.filter { it.tMs in t0..t1 }.map { it.hPa }
        return if (xs.isEmpty()) null else median(xs)
    }

    fun lastGoodGnss(beforeMs: Long): GnssSample? = gnss.lastOrNull { it.tMs <= beforeMs && it.good }

    fun copy(keepMs: Long = this.keepMs): SensorBuffer {
        val b = SensorBuffer(keepMs)
        b.pressure.addAll(pressure); b.steps.addAll(steps); b.headings.addAll(headings); b.gnss.addAll(gnss)
        return b
    }

    private fun trim(now: Long) {
        val cut = now - keepMs
        while (pressure.isNotEmpty() && pressure.first().tMs < cut) pressure.removeFirst()
        while (steps.isNotEmpty() && steps.first() < cut) steps.removeFirst()
        while (headings.isNotEmpty() && headings.first().tMs < cut) headings.removeFirst()
        while (gnss.isNotEmpty() && gnss.first().tMs < cut) gnss.removeFirst()
    }
}
