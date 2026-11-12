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
    /** Rotation applied by the lift-door alignment, if any (debug). */
    val alignDeg: Double? = null,
    /** Which starting-point assumption is in use (debug). */
    val ref: String? = null,
    /** Ask the user which floor they are on (they just stepped off a lift or stairs). */
    val askFloor: Boolean = false,
    /** Best guess for that question, pre-selected. */
    val guessLabel: String? = null,
)

/**
 * Return-trip guidance for one starting-point assumption (design doc 5.8-5.10).
 *
 * Vertical: height relative to the car = change measured on this trip + one stored
 * difference (P1), so overnight weather does not matter.
 * Horizontal: once settled on the car floor the user is assumed to be at the same
 * lift/stair door used when leaving. The first ~3 m walked out of that door is
 * compared with the stored door direction; if they disagree the stored path is
 * rotated, which removes any difference in how the phone was carried (D02).
 */
class Guidance(
    private val rec: ParkingRecord,
    val ref: StartRef,
    private val floorH: Double = FloorEstimator.DEFAULT_FLOOR_H,
    private val learned: Boolean = false,
    private val stepScale: Double = 1.0,
) {
    enum class StartRef { GROUND, WALK_END }

    private val smoother = PressureSmoother()
    // Faster stop detection than on the walk-out: the user is waiting for the arrow.
    private val segmenter = VerticalSegmenter(stopHoldMs = 1_500)
    private val startSamples = mutableListOf<Double>()
    private var hStart: Double? = null
    private var hNow: Double? = null
    val tracker = HeadingTracker()
    private var pdr: Pdr? = null
    private var anchored = false
    private var offFloorSinceMs = -1L
    private var wpIdx = 0
    private val recentSteps = ArrayDeque<Long>()
    private var lastT = 0L

    private var alignDone = false
    private var rotationDeg = 0.0

    private var hStartRelCar: Double? = when (ref) {
        StartRef.GROUND -> rec.floor.dhGroundM?.let { -it }
        StartRef.WALK_END -> rec.dhEndM
    }

    /** True once the user told us which floor they are on (overrides both assumptions). */
    var calibrated = false
        private set

    /**
     * The user says they are [levelsAboveCar] floors above the car right now
     * (negative = below). Replaces the stored reference, so any error in it
     * (sloped sites, GNSS lingering, a start point elsewhere) is gone from here on.
     */
    fun calibrate(levelsAboveCar: Int) {
        val d = heightFromStart() ?: return
        hStartRelCar = levelsAboveCar * floorH - d
        calibrated = true
        if (levelsAboveCar != 0 && anchored) {
            anchored = false; pdr = null; wpIdx = 0; alignDone = false; rotationDeg = 0.0
        }
    }

    val isAnchored: Boolean get() = anchored
    val hasVerticalReference: Boolean get() = hStartRelCar != null

    fun onPressure(tMs: Long, hPa: Double) {
        lastT = maxOf(lastT, tMs)
        val h = Baro.altitudeM(smoother.push(hPa))
        hNow = h
        if (hStart == null) {
            startSamples += h
            if (startSamples.size >= 5) hStart = median(startSamples)
        }
        val seg = segmenter.onHeight(tMs, h)
        if (segmenter.moving) arrivedAtMs = -1L
        if (seg != null) arrivedAtMs = seg.endMs
        updateAnchor(tMs)
    }

    /** End of the last vertical move; -1 while moving or before any move. */
    private var arrivedAtMs = -1L

    /**
     * True right after the user steps off a lift or stairs (a vertical move ended
     * and they started walking) and has not told us their floor yet. Asking here
     * costs one tap and removes every reference error at once.
     */
    fun wantsFloorConfirm(): Boolean {
        if (calibrated || arrivedAtMs < 0) return false
        return recentSteps.count { it >= arrivedAtMs } >= 2
    }

    fun onStep(tMs: Long) {
        lastT = maxOf(lastT, tMs)
        segmenter.onStep(tMs)
        recentSteps.addLast(tMs)
        while (recentSteps.isNotEmpty() && recentSteps.first() < tMs - 30_000) recentSteps.removeFirst()
        pdr?.onStep(tMs)
    }

    fun onHeading(tMs: Long, deg: Double, up: DoubleArray? = null) {
        lastT = maxOf(lastT, tMs)
        tracker.onSample(tMs, deg, up)
    }

    /** Height change since guidance started, metres (positive = went up). */
    fun heightFromStart(): Double? {
        val hs = hStart ?: return null
        val hn = hNow ?: return null
        return hn - hs
    }

    /** Height of the user above the car in metres; null when no vertical reference exists. */
    fun hRelCar(): Double? {
        val d = heightFromStart() ?: return null
        val rel = hStartRelCar ?: return null
        return d + rel
    }

    fun isSettled(tMs: Long): Boolean = segmenter.isSettled(tMs)

    /** Distance from the estimated user position to the car, for the "found" error. */
    fun distanceToCar(): Double? {
        val car = rec.carInAnchorFrame ?: return null
        val p = pdr ?: return null
        return (car.rotatedBy(rotationDeg) - p.pos).norm()
    }

    fun state(tMs: Long): GuideState {
        val label = rec.displayLabel
        if (hStart == null) return simple(GuideMode.CALIBRATING, label, "기압을 재는 중이에요")
        val r = hRelCar()
            ?: return memory(label, "층 기준이 없어요. ${ro(label ?: "기록된 층")} 가세요", "T4")

        if (!anchored) {
            // Half a floor is the boundary between "this floor" and the next one.
            if (abs(r) >= ARRIVE_FRACTION * floorH) {
                val n = max(1, (abs(r) / floorH).roundToInt())
                val down = r > 0
                val far = abs(r) > 2.5 * floorH
                val text = if (far) {
                    if (down) "${ro(label ?: "차 있는 층")} 내려가세요" else "${ro(label ?: "차 있는 층")} 올라가세요"
                } else {
                    if (down) "▼ ${n}개 층 더 내려가세요" else "▲ ${n}개 층 올라가세요"
                }
                val floorsToGo = if (far) null else if (down) n else -n
                return GuideState(GuideMode.FLOOR, label, floorsToGo, null, null, 90.0, text, null, tier(), r, null, null, refName())
            }
            return GuideState(GuideMode.SETTLING, label, 0, null, null, 90.0, "이 층이에요. 엘리베이터에서 나오세요", null, tier(), r, null, null, refName())
        }

        val car0 = rec.carInAnchorFrame
            ?: return memory(label, "이 층이에요. 위치 기록이 없어 주변을 둘러보세요", "T3")
        val p = pdr!!
        tryAlign(p)
        val car = car0.rotatedBy(rotationDeg)
        val u = p.pos
        val wps = rec.waypointsFromAnchor.ifEmpty { listOf(car0) }.map { it.rotatedBy(rotationDeg) }
        while (wpIdx < wps.lastIndex && passedWaypoint(u, wps[wpIdx], wps[wpIdx + 1])) wpIdx++
        val target = wps[wpIdx]
        var remaining = (target - u).norm()
        for (k in wpIdx until wps.lastIndex) remaining += (wps[k + 1] - wps[k]).norm()
        val dCar = (car - u).norm()
        val sigma = 1.5 + 0.05 * (rec.pathLengthM + p.distanceM)
        val sigmaPsi = if (learned) 8.0 else if (alignDone) 15.0 else 25.0
        val pointing = tracker.pointing(tMs)
        val arrow = pointing?.let { angleDiff((target - u).bearingDeg(), it) }
        val cone = (Math.toDegrees(atan(sigma / max(remaining, 0.5))) + sigmaPsi).coerceIn(10.0, 90.0)
        val beep = beepInterval(remaining, sigma)
        val align = if (alignDone) rotationDeg else null

        if (dCar < max(4.0, 1.5 * sigma)) {
            return GuideState(GuideMode.ARRIVE, label, 0, dCar, arrow, cone, "이 근처예요", beep, tier(), r, sigma, align, refName())
        }
        val (mode, text) = if (wpIdx < wps.lastIndex) {
            GuideMode.STEP to "${(target - u).norm().roundToInt()} m 간 뒤 ${turnWord(wps, wpIdx)}"
        } else {
            GuideMode.VECTOR to "화살표 방향으로 가세요"
        }
        return GuideState(mode, label, 0, remaining, arrow, cone, text, beep, tier(), r, sigma, align, refName())
    }

    /**
     * Lift-door alignment: the first straight ~3 m out of the door should point
     * the way the stored door bearing says. A large mismatch means the two trips
     * were measured in different frames (pocket vs hand), so rotate the stored path.
     */
    private fun tryAlign(p: Pdr) {
        if (alignDone) return
        val door = rec.doorBearingDeg
        if (door == null) { alignDone = true; return }
        if (p.distanceM > 10.0) { alignDone = true; return }
        val disp = p.pos.norm()
        if (disp < 3.0 || disp < 0.8 * p.distanceM) return
        val delta = angleDiff(p.pos.bearingDeg(), door)
        rotationDeg = if (abs(delta) >= 20.0) delta else 0.0
        alignDone = true
    }

    private fun updateAnchor(tMs: Long) {
        val r = hRelCar() ?: return
        if (anchored) {
            val onCarFloor = abs(r) < LEAVE_FRACTION * floorH
            // Left the car floor again (e.g. wrong stairs): start over.
            if (!onCarFloor) {
                if (offFloorSinceMs < 0) offFloorSinceMs = tMs
                if (tMs - offFloorSinceMs > 5_000) {
                    anchored = false; pdr = null; wpIdx = 0; alignDone = false; rotationDeg = 0.0
                }
            } else offFloorSinceMs = -1L
            return
        }
        if (abs(r) < ARRIVE_FRACTION * floorH && segmenter.isSettled(tMs, minMs = 1_000)) {
            anchored = true
            offFloorSinceMs = -1L
            val p = Pdr(stepScale, rec.gridAngleDeg, tracker = tracker, ownsTracker = false)
            // Steps taken since the lift stopped already count (walking out of the lift).
            val since = segmenter.lastPlateauStartMs
            if (since > 0) recentSteps.filter { it >= since }.forEach { p.onStep(it) }
            pdr = p
            wpIdx = 0
        }
    }

    private fun refName() = if (ref == StartRef.GROUND) "지상 출발" else "도착지 출발"

    private fun tier(): String = when {
        rec.carInAnchorFrame == null -> "T3"
        learned -> "T1"
        else -> "T2"
    }

    private fun simple(mode: GuideMode, label: String?, text: String) =
        GuideState(mode, label, null, null, null, 90.0, text, null, tier(), null, null, null, refName())

    private fun memory(label: String?, text: String, tier: String) =
        GuideState(GuideMode.MEMORY, label, null, null, null, 90.0, text, null, tier, hRelCar(), null, null, refName())

    companion object {
        /** Closer than this fraction of a floor counts as arriving on the car floor. */
        const val ARRIVE_FRACTION = 0.5
        /** Once there, leaving needs a bigger change (hysteresis, B20). */
        const val LEAVE_FRACTION = 0.6

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

/**
 * One return trip. Where the user starts is not known for sure: at home (where
 * the walk-out ended) or at street level. Both assumptions are tracked and the
 * one the barometer agrees with is shown (fixes the "GPS fix at a window" trap).
 * Open-air parkings with a GNSS position get a GPS arrow instead (F05).
 */
class GuideSession(
    private val rec: ParkingRecord,
    private val floorH: Double = FloorEstimator.DEFAULT_FLOOR_H,
    learned: Boolean = false,
    stepScale: Double = 1.0,
) {
    private val endGuide = rec.dhEndM?.let { Guidance(rec, Guidance.StartRef.WALK_END, floorH, learned, stepScale) }
    private val groundGuide = rec.floor.dhGroundM?.let { Guidance(rec, Guidance.StartRef.GROUND, floorH, learned, stepScale) }
    private val all = listOfNotNull(endGuide, groundGuide)
    private val pointingTracker = HeadingTracker()

    var active: Guidance? = endGuide ?: groundGuide
        private set
    private var strongFixes = 0
    private var lastFix: GnssSample? = null
    private var lastT = 0L

    /** Bumps whenever [active] changes, so the caller can log it. */
    var switches = 0
        private set

    /** Car floor as corrected by the user during this trip. */
    var carLabel: String? = rec.displayLabel
        private set

    /**
     * "I'm on [currentLabel], the car is on [carLabel]": both start assumptions are
     * replaced by this one fact. Returns false when a label cannot be read.
     */
    fun calibrate(currentLabel: String, carLabel: String): Boolean {
        val cur = SiteBook.levelIndex(currentLabel) ?: return false
        val car = SiteBook.levelIndex(carLabel) ?: return false
        this.carLabel = carLabel
        all.forEach { it.calibrate(cur - car) }
        return all.isNotEmpty()
    }

    val isAnchored: Boolean get() = active?.isAnchored == true

    fun onPressure(tMs: Long, hPa: Double) {
        lastT = maxOf(lastT, tMs)
        all.forEach { it.onPressure(tMs, hPa) }
        reselect(tMs)
    }

    fun onStep(tMs: Long) {
        lastT = maxOf(lastT, tMs)
        all.forEach { it.onStep(tMs) }
    }

    fun onHeading(tMs: Long, deg: Double, up: DoubleArray? = null) {
        lastT = maxOf(lastT, tMs)
        all.forEach { it.onHeading(tMs, deg, up) }
        pointingTracker.onSample(tMs, deg, up)
    }

    fun onGnss(s: GnssSample) {
        lastT = maxOf(lastT, s.tMs)
        if (s.good) lastFix = s
        // A strong fix before any vertical move suggests the user is outdoors at street level.
        val moved = abs(active?.heightFromStart() ?: 0.0) > 2.0
        val strong = s.good && s.accuracyM <= 12.0 && (s.satellitesUsed < 0 || s.satellitesUsed >= 6)
        if (!moved && strong) {
            strongFixes++
            if (strongFixes >= 3 && groundGuide != null && active !== groundGuide) setActive(groundGuide)
        }
    }

    fun distanceToCar(): Double? = active?.distanceToCar()

    fun state(tMs: Long): GuideState {
        val geo = rec.carGeo
        if (rec.carInAnchorFrame == null && rec.anchorMode == null && geo != null) return gnssState(tMs, geo)
        val g = active
            ?: return GuideState(GuideMode.MEMORY, carLabel, null, null, null, 90.0,
                "층 기준이 없어요. ${ro(carLabel ?: "기록된 층")} 가세요", null, "T4", null, null)
        val s = g.state(tMs)
        val label = carLabel
        val old = s.targetLabel
        val fixed = if (label != null && old != null && label != old) {
            s.copy(targetLabel = label, text = s.text.replace(ro(old), ro(label)).replace(old, label))
        } else if (label != null && old == null) s.copy(targetLabel = label) else s
        var out = if (g.calibrated) fixed.copy(ref = "층 직접 지정") else fixed
        if (g.wantsFloorConfirm()) {
            val r = g.hRelCar()
            val guess = if (label != null && r != null) SiteBook.shiftLabel(label, (r / floorH).roundToInt()) else null
            out = out.copy(askFloor = true, guessLabel = guess)
        }
        return out
    }

    private fun reselect(tMs: Long) {
        val a = active ?: return
        if (a.calibrated) return
        val b = all.firstOrNull { it !== a } ?: return
        val ra = a.hRelCar() ?: return
        val rb = b.hRelCar() ?: return
        // On a settled level the barometer agrees with one assumption and clearly not the other.
        if (a.isSettled(tMs) && abs(ra) > 2.5 * floorH && abs(rb) < 0.4 * floorH) { setActive(b); return }
        // Starting at street level, nobody goes far deeper than the car: it was the other start.
        if (a === groundGuide && ra < -2.5 * floorH && endGuide != null) setActive(endGuide)
    }

    private fun setActive(g: Guidance) {
        active = g
        switches++
    }

    private fun gnssState(tMs: Long, car: GeoFix): GuideState {
        val label = rec.displayLabel
        val fix = lastFix
        if (fix == null || tMs - fix.tMs > 5_000) {
            return GuideState(GuideMode.MEMORY, label, null, null, null, 90.0, "GPS를 찾는 중이에요", 1.5, "T2", null, null, null, "GPS")
        }
        val v = GeoFix(fix.lat, fix.lon, fix.accuracyM).vectorTo(car)
        val d = v.norm()
        val sigma = max(fix.accuracyM, car.accuracyM)
        val pointing = pointingTracker.pointing(tMs)
        val arrow = pointing?.let { angleDiff(v.bearingDeg(), it) }
        val cone = (Math.toDegrees(atan(sigma / max(d, 0.5))) + 10.0).coerceIn(10.0, 90.0)
        val beep = Guidance.beepInterval(d, sigma)
        return if (d < max(5.0, 1.2 * sigma)) {
            GuideState(GuideMode.ARRIVE, label, 0, d, arrow, cone, "이 근처예요", beep, "T2", null, sigma, null, "GPS")
        } else {
            GuideState(GuideMode.VECTOR, label, 0, d, arrow, cone, "화살표 방향으로 가세요", beep, "T2", null, sigma, null, "GPS")
        }
    }
}
