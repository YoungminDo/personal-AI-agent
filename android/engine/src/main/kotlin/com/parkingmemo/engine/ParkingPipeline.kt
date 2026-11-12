package com.parkingmemo.engine

/**
 * The parking side of the engine as one state machine. The Android service and
 * the offline log replayer both drive this class, so field logs reproduce
 * exactly what the phone computed.
 *
 * IDLE -> DRIVING -> WAITING_WALK -> WALK_OUT -> (record saved) -> IDLE
 */
class ParkingPipeline(
    private val sites: SiteBook,
    private val stepScale: Double = 1.0,
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
) {
    enum class Phase { IDLE, DRIVING, WAITING_WALK, WALK_OUT }

    sealed interface Output {
        data class WalkStarted(val originMs: Long) : Output
        data class Saved(val record: ParkingRecord) : Output
        data object Cancelled : Output
        data object GaveUp : Output
    }

    var phase = Phase.IDLE
        private set
    val buffer = SensorBuffer()
    private val detector = ParkingDetector()
    private var walk: WalkOutBuilder? = null
    private var nowMs = 0L
    private var pendingEndMs: Long? = null

    /** A manual end was requested and is waiting for the user to stop moving vertically. */
    val isFinishing: Boolean get() = pendingEndMs != null

    val walkProgress: WalkOutBuilder? get() = walk

    fun carConnected(tMs: Long): Output? {
        nowMs = tMs
        val wasWalking = phase == Phase.WALK_OUT
        val d = detector.onCarConnected()
        walk = null
        pendingEndMs = null
        phase = Phase.DRIVING
        return if (wasWalking || d is ParkingDetector.Decision.Cancelled) Output.Cancelled else null
    }

    fun carDisconnected(tMs: Long): Output? {
        nowMs = tMs
        if (phase == Phase.DRIVING || phase == Phase.IDLE) {
            detector.onCarDisconnected(tMs, buffer.steps.toList())
            phase = Phase.WAITING_WALK
        }
        return null
    }

    /** "Record parking now" button: skip detection, start from this moment. */
    fun manualPark(tMs: Long): Output = startWalk(tMs, tMs)

    fun pressure(tMs: Long, hPa: Double) {
        nowMs = tMs
        buffer.addPressure(tMs, hPa)
        walk?.onPressure(tMs, hPa)
    }

    fun heading(tMs: Long, deg: Double, up: DoubleArray? = null) {
        nowMs = tMs
        buffer.addHeading(tMs, deg, up)
        walk?.onHeading(tMs, deg, up)
    }

    fun gnss(s: GnssSample) {
        nowMs = s.tMs
        buffer.addGnss(s)
        walk?.onGnss(s)
    }

    fun step(tMs: Long): Output? {
        nowMs = tMs
        buffer.addStep(tMs)
        when (phase) {
            Phase.WAITING_WALK -> {
                val d = detector.onStep(tMs)
                if (d is ParkingDetector.Decision.Confirmed) return startWalk(d.originMs, tMs)
            }
            Phase.WALK_OUT -> walk?.onStep(tMs)
            else -> Unit
        }
        return null
    }

    fun tick(tMs: Long): Output? {
        nowMs = tMs
        when (phase) {
            Phase.WAITING_WALK -> if (detector.tick(tMs) is ParkingDetector.Decision.GaveUp) {
                phase = Phase.IDLE
                return Output.GaveUp
            }
            Phase.WALK_OUT -> {
                val w = walk ?: return null
                pendingEndMs?.let { p ->
                    if (w.readyToFinish(tMs) || tMs - p > 15_000) return finishWalk(tMs, WalkEnd.MANUAL)
                }
                val end = w.checkEnd(tMs) ?: return null
                return finishWalk(tMs, end)
            }
            else -> Unit
        }
        return null
    }

    /** "I've arrived": finishes now, or a few seconds later if the user is still in a moving lift. */
    fun endWalkManually(tMs: Long): Output? {
        if (phase != Phase.WALK_OUT) return null
        val w = walk ?: return null
        if (w.readyToFinish(tMs)) return finishWalk(tMs, WalkEnd.MANUAL)
        pendingEndMs = tMs
        return null
    }

    private fun finishWalk(tMs: Long, end: WalkEnd): Output {
        val rec = walk!!.finish(tMs, end)
        walk = null
        pendingEndMs = null
        phase = Phase.IDLE
        return Output.Saved(rec)
    }

    private fun startWalk(originMs: Long, tMs: Long): Output {
        val wallAtOrigin = wallClockMs() - (tMs - originMs)
        walk = WalkOutBuilder(wallAtOrigin, originMs, buffer, stepScale, sites)
        pendingEndMs = null
        phase = Phase.WALK_OUT
        return Output.WalkStarted(originMs)
    }
}
