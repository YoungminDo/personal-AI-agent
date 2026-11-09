package com.parkingmemo.engine

/**
 * Decides when the user has actually left the car (P3: evidence, not one signal).
 *
 * Car Bluetooth disconnect alone is not enough: it can be a glitch (A07), the
 * user can sit in the car for a while (A05), or they can already be walking
 * away with the engine running (A06). Parking is confirmed once a walking run
 * follows the disconnect; the origin is rewound to the start of that run.
 */
class ParkingDetector(
    private val minSteps: Int = 4,
    private val maxGapMs: Long = 2_500,
    private val waitMaxMs: Long = 60 * 60_000L,
) {
    sealed interface Decision {
        data object None : Decision
        data class Confirmed(val originMs: Long) : Decision
        data object Cancelled : Decision
        data object GaveUp : Decision
    }

    private var disconnectAt: Long? = null
    private val run = mutableListOf<Long>()

    val waiting: Boolean get() = disconnectAt != null

    /** [recentSteps]: step times from the rolling buffer (to catch A06). */
    fun onCarDisconnected(tMs: Long, recentSteps: List<Long>) {
        disconnectAt = tMs
        run.clear()
        run += walkingRunEndingAt(recentSteps, tMs)
    }

    /** Any reconnect while waiting means the car is in use again (A07, A10). */
    fun onCarConnected(): Decision {
        if (disconnectAt == null) return Decision.None
        disconnectAt = null
        run.clear()
        return Decision.Cancelled
    }

    fun onStep(tMs: Long): Decision {
        if (disconnectAt == null) return Decision.None
        if (run.isNotEmpty() && tMs - run.last() > maxGapMs) run.clear()
        run += tMs
        if (run.size >= minSteps) {
            val origin = run.first()
            disconnectAt = null
            run.clear()
            return Decision.Confirmed(origin)
        }
        return Decision.None
    }

    /** Phone left in the car or never walked away (A08). */
    fun tick(tMs: Long): Decision {
        val d = disconnectAt ?: return Decision.None
        if (tMs - d > waitMaxMs) {
            disconnectAt = null
            run.clear()
            return Decision.GaveUp
        }
        return Decision.None
    }

    private fun walkingRunEndingAt(steps: List<Long>, tMs: Long): List<Long> {
        val before = steps.filter { it <= tMs }
        if (before.isEmpty() || tMs - before.last() > maxGapMs) return emptyList()
        var i = before.lastIndex
        while (i > 0 && before[i] - before[i - 1] <= maxGapMs) i--
        return before.subList(i, before.size)
    }
}
