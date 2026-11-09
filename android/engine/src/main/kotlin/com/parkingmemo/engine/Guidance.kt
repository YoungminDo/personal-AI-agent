package com.parkingmemo.engine

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.roundToInt

enum class GuideMode { CALIBRATING, FLOOR, SETTLING, STEP, VECTOR, MEMORY, ARRIVE }

data class GuideState(
    val mode: GuideMode,
    val targetLabel: String?,
    /** Floors to go: positive = go down, negative = go up, 0 = on the car floor. Null = far/unknown. */
    val floorsToGo: Int?,
    /** Remaining walking distance to the car in metres. */
    val distanceM: Double?,
    /** Arrow direction relative to where the phone points, degrees (0 = straight ahead). */
    val arrowDeg: Double?,
    /** Half-width of the uncertainty cone in degrees (narrow = confident). */
    val coneDeg: Double,
    val text: String,
    /** Seconds between vibration/beep pulses, or null for silence. */
    val beepIntervalS: Double?,
    val tier: String,
    /** User height relative to the car in metres (debug). */
    val hRelCarM: Double?,
    val sigmaM: Double?,
)

/**
 * Return-trip guidance (design doc 5.8-5.10).
 *
 * Vertical: the current height relative to the car is built only from changes
 * measured during this trip plus one stored difference (P1), so overnight
 * weather does not matter. Horizontal: once the user settles on the car floor
 * the anchor is assumed to be the same exit used when leaving, and the stored
 * path is followed back to the car.
 */
class Guidance(
    private val rec: ParkingRecord,
    private val ref: StartRef,
    private val floorH: Double = FloorEstimator.DEFAULT_FLOOR_H,
    private val learned: Boolean = false,
    private val stepScale: Double = 1.0,
) {
    enum class StartRef { GROUND, WALK_END }

    private val smoother = PressureSmoother()
    private val segmenter = VerticalSegmenter()
    private val startSamples = mutableListOf<Double>()
    private var hStart: Double? = null
    private var hNow: Double? = null
    private val arrowHeading = CircularEma(0.5)
    private var lastHeadingDeg: Double? = null
    private var pdr: Pdr? = null
    private var anchored = false
    private var offFloorSinceMs = -1L
    private var wpIdx = 0
    private val recentSteps = ArrayDeque<Long>()

    private val hStartRelCar: Double? = when (ref) {
        StartRef.GROUND -> rec.floor.dhGroundM?.let { -it }
        StartRef.WALK_END -> rec.dhEndM
    }

    val isAnchored: Boolean get() = anchored

    fun onPressure(tMs: Long, hPa: Double) {
        val h = Baro.altitudeM(smoother.push(hPa))
        hNow = h
        if (hStart == null) {
            startSamples += h
            if (startSamples.size >= 5) hStart = median(startSamples)
        }
        segmenter.onHeight(tMs, h)
        updateAnchor(tMs)
    }

    fun onStep(tMs: Long) {
        segmenter.onStep(tMs)
        recentSteps.addLast(tMs)
        while (recentSteps.isNotEmpty() && recentSteps.first() < tMs - 30_000) recentSteps.removeFirst()
        pdr?.onStep(tMs)
    }

    fun onHeading(deg: Double) {
        lastHeadingDeg = deg
        arrowHeading.push(deg)
        pdr?.onHeading(deg)
    }

    /** Height of the user above the car in metres; null when no vertical reference exists. */
    fun hRelCar(): Double? {
        val hs = hStart ?: return null
        val hn = hNow ?: return null
        val rel = hStartRelCar ?: return null
        return (hn - hs) + rel
    }

    /** Distance from the estimated user position to the car, for the "found" error. */
    fun distanceToCar(): Double? {
        val car = rec.carInAnchorFrame ?: return null
        val p = pdr ?: return null
        return (car - p.pos).norm()
    }

    fun state(tMs: Long): GuideState {
        val label = rec.displayLabel
        if (hStart == null) return simple(GuideMode.CALIBRATING, label, "기압을 재는 중이에요", null)
        val r = hRelCar()
            ?: return memory(label, "층 기준이 없어요. ${label ?: "기록된 층"}으로 가세요", "T4")

        if (!anchored) {
            if (abs(r) >= 0.6 * floorH) {
                val n = max(1, (abs(r) / floorH).roundToInt())
                val down = r > 0
                val text = if (abs(r) > 2.5 * floorH) {
                    if (down) "${label ?: "차 있는 층"}으로 내려가세요" else "${label ?: "차 있는 층"}으로 올라가세요"
                } else {
                    if (down) "▼ ${n}개 층 더 내려가세요" else "▲ ${n}개 층 올라가세요"
                }
                val floorsToGo = if (abs(r) > 2.5 * floorH) null else if (down) n else -n
                return GuideState(GuideMode.FLOOR, label, floorsToGo, null, null, 90.0, text, null, tier(), r, null)
            }
            return GuideState(GuideMode.SETTLING, label, 0, null, null, 90.0, "이 층이에요. 잠시 멈춰 주세요", null, tier(), r, null)
        }

        val car = rec.carInAnchorFrame
            ?: return memory(label, "이 층이에요. 위치 기록이 없어 주변을 둘러보세요", "T3")
        val p = pdr!!
        val u = p.pos
        val wps = rec.waypointsFromAnchor.ifEmpty { listOf(car) }
        while (wpIdx < wps.lastIndex && passedWaypoint(u, wps[wpIdx], wps[wpIdx + 1])) wpIdx++
        val target = wps[wpIdx]
        var remaining = (target - u).norm()
        for (k in wpIdx until wps.lastIndex) remaining += (wps[k + 1] - wps[k]).norm()
        val dCar = (car - u).norm()
        val sigma = 1.5 + 0.05 * (rec.pathLengthM + p.distanceM)
        val sigmaPsi = if (learned) 8.0 else 20.0
        val heading = arrowHeading.value
        val arrow = heading?.let { angleDiff((target - u).bearingDeg(), it) }
        val cone = (Math.toDegrees(atan(sigma / max(remaining, 0.5))) + sigmaPsi).coerceIn(10.0, 90.0)
        val beep = beepInterval(remaining, sigma)

        if (dCar < max(4.0, 1.5 * sigma)) {
            return GuideState(GuideMode.ARRIVE, label, 0, dCar, arrow, cone, "이 근처예요", beep, tier(), r, sigma)
        }
        val (mode, text) = if (wpIdx < wps.lastIndex) {
            GuideMode.STEP to "${(target - u).norm().roundToInt()} m 간 뒤 ${turnWord(wps, wpIdx)}"
        } else {
            GuideMode.VECTOR to "화살표 방향으로 가세요"
        }
        return GuideState(mode, label, 0, remaining, arrow, cone, text, beep, tier(), r, sigma)
    }

    private fun updateAnchor(tMs: Long) {
        val r = hRelCar() ?: return
        val onCarFloor = abs(r) < 0.6 * floorH
        if (anchored) {
            // Left the car floor again (e.g. took the stairs the wrong way): start over.
            if (!onCarFloor) {
                if (offFloorSinceMs < 0) offFloorSinceMs = tMs
                if (tMs - offFloorSinceMs > 5_000) { anchored = false; pdr = null; wpIdx = 0 }
            } else offFloorSinceMs = -1L
            return
        }
        if (onCarFloor && segmenter.isSettled(tMs)) {
            anchored = true
            offFloorSinceMs = -1L
            val p = Pdr(stepScale, rec.gridAngleDeg)
            lastHeadingDeg?.let { p.onHeading(it) }
            // Steps taken since the lift stopped already count (walking out of the lift).
            val since = segmenter.lastPlateauStartMs
            if (since > 0) recentSteps.filter { it >= since }.forEach { p.onStep(it) }
            pdr = p
            wpIdx = 0
        }
    }

    private fun tier(): String = when {
        rec.carInAnchorFrame == null -> "T3"
        learned -> "T1"
        else -> "T2"
    }

    private fun simple(mode: GuideMode, label: String?, text: String, r: Double?) =
        GuideState(mode, label, null, null, null, 90.0, text, null, tier(), r, null)

    private fun memory(label: String?, text: String, tier: String) =
        GuideState(GuideMode.MEMORY, label, null, null, null, 90.0, text, null, tier, hRelCar(), null)

    companion object {
        /** Design doc 5.9: faster pulses closer in; a steady search pulse when the estimate is too uncertain. */
        fun beepInterval(distanceM: Double, sigmaM: Double): Double =
            if (sigmaM < 0.5 * distanceM) (0.1 + 0.06 * distanceM).coerceIn(0.15, 2.0) else 1.5

        /**
         * A waypoint is done when the user reaches it, or has cut the corner and is
         * already walking along the next leg.
         */
        fun passedWaypoint(u: Vec2, wp: Vec2, next: Vec2): Boolean {
            if ((wp - u).norm() < 2.5) return true
            val leg = next - wp
            val len2 = leg.x * leg.x + leg.y * leg.y
            if (len2 < 1e-6) return true
            val rel = u - wp
            val t = (rel.x * leg.x + rel.y * leg.y) / len2
            if (t <= 0.0) return false
            val closest = wp + leg * t.coerceAtMost(1.0)
            return (u - closest).norm() < 6.0
        }

        fun turnWord(wps: List<Vec2>, i: Int): String {
            if (i >= wps.lastIndex) return "도착"
            val prev = if (i == 0) Vec2.ZERO else wps[i - 1]
            val incoming = wps[i] - prev
            val outgoing = wps[i + 1] - wps[i]
            val d = angleDiff(outgoing.bearingDeg(), incoming.bearingDeg())
            return when {
                d > 30 -> "오른쪽으로"
                d < -30 -> "왼쪽으로"
                else -> "계속 직진"
            }
        }
    }
}
