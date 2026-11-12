package com.parkingmemo.engine

import kotlin.math.abs
import kotlin.math.floor

enum class WalkEnd { GNSS_OUTDOOR, STATIONARY, TIMEOUT, MANUAL }

/** Everything remembered about one parking. Positions are metres, north/east frame. */
data class ParkingRecord(
    val parkedAtWallMs: Long,
    val siteKey: String?,
    val carPressureHpa: Double?,
    /** Car height vs ground, measured while driving in (ground = last good GNSS fix). */
    val dhGroundDriveM: Double?,
    /** Car height vs ground, measured walking out to an outdoor GNSS fix. */
    val dhGroundWalkM: Double?,
    /** Where the walk-out ended (home, office, street) relative to the car, metres. */
    val dhEndM: Double?,
    val endKind: WalkEnd,
    /** How the user left the car floor (the anchor), e.g. ELEVATOR. */
    val anchorMode: VerticalMode?,
    /** Car position relative to the anchor (the point where the user left the car floor). */
    val carInAnchorFrame: Vec2?,
    /** Waypoints from the anchor back to the car; the last element is the car. */
    val waypointsFromAnchor: List<Vec2>,
    val gridAngleDeg: Double?,
    /** Walking distance from the car to the anchor. */
    val pathLengthM: Double,
    val segments: List<VerticalSegment>,
    val floor: FloorEstimate,
    /** Label the user typed in, overriding the estimate (D10). */
    val userLabel: String? = null,
    /**
     * Bearing from the anchor back along the last ~3 m walked before it, i.e. the
     * direction a person faces when stepping out of the lift door. Used to align
     * the return trip when the phone was carried differently (pocket vs hand).
     */
    val doorBearingDeg: Double? = null,
    /** GNSS position of the car when it was parked in the open (F05). */
    val carGeo: GeoFix? = null,
) {
    val displayLabel: String? get() = userLabel ?: floor.label
}

/**
 * Records the walk from the car to wherever the user ends up, then turns it
 * into a [ParkingRecord]. Fed live by the app, or by [LogReplay] offline.
 */
class WalkOutBuilder(
    private val parkedAtWallMs: Long,
    private val originMs: Long,
    pre: SensorBuffer,
    stepScale: Double = 1.0,
    private val sites: SiteBook = SiteBook(),
    private val timeoutMs: Long = 15 * 60_000L,
    private val stationaryMs: Long = 5 * 60_000L,
    private val outdoorHoldMs: Long = 30_000L,
) {
    private val buf = pre.copy(keepMs = 2 * 60 * 60_000L)
    private val smoother = PressureSmoother()
    private val segmenter = VerticalSegmenter()
    private val pdr = Pdr(stepScale)
    private var anchorTrackIdx: Int? = null
    private var anchorMode: VerticalMode? = null
    private var lastStepMs = originMs
    private var gnssGoodSinceMs: Long? = null
    private var latestMs = originMs

    init {
        // Replay what happened between the walking origin and now (A06 rewind).
        class Ev(val t: Long, val kind: Int, val v: Double, val up: DoubleArray? = null)
        val evs = mutableListOf<Ev>()
        pre.pressure.filter { it.tMs >= originMs - 10_000 }.forEach { evs += Ev(it.tMs, 0, it.hPa) }
        pre.headings.filter { it.tMs >= originMs - 5_000 }.forEach { evs += Ev(it.tMs, 1, it.deg, it.up) }
        pre.steps.filter { it >= originMs }.forEach { evs += Ev(it, 2, 0.0) }
        evs.sortWith(compareBy({ it.t }, { it.kind }))
        for (e in evs) when (e.kind) {
            0 -> feedPressure(e.t, e.v)
            1 -> pdr.onHeading(e.t, e.v, e.up)
            else -> feedStep(e.t)
        }
    }

    fun onPressure(tMs: Long, hPa: Double) {
        buf.addPressure(tMs, hPa)
        feedPressure(tMs, hPa)
    }

    fun onStep(tMs: Long) {
        buf.addStep(tMs)
        feedStep(tMs)
    }

    fun onHeading(tMs: Long, deg: Double, up: DoubleArray? = null) {
        buf.addHeading(tMs, deg, up)
        pdr.onHeading(tMs, deg, up)
        latestMs = maxOf(latestMs, tMs)
    }

    fun onGnss(s: GnssSample) {
        buf.addGnss(s)
        latestMs = maxOf(latestMs, s.tMs)
        if (s.tMs < originMs) return
        gnssGoodSinceMs = if (s.good) (gnssGoodSinceMs ?: s.tMs) else null
    }

    fun checkEnd(tMs: Long): WalkEnd? {
        if (tMs - originMs > timeoutMs) return WalkEnd.TIMEOUT
        val gs = gnssGoodSinceMs
        if (gs != null && tMs - gs >= outdoorHoldMs && tMs - originMs > 20_000) return WalkEnd.GNSS_OUTDOOR
        val movedEnough = segmenter.segments.isNotEmpty() || pdr.track.size >= 20
        if (tMs - lastStepMs > stationaryMs && movedEnough) return WalkEnd.STATIONARY
        return null
    }

    val stepsSoFar: Int get() = pdr.track.size

    /**
     * False while the user is still moving vertically or has only just stopped.
     * A manual "I've arrived" waits for this so the arrival height is a settled
     * plateau, not an average that includes the end of the lift ride.
     */
    fun readyToFinish(tMs: Long): Boolean =
        !segmenter.moving && (segmenter.lastPlateauStartMs < 0 || tMs - segmenter.lastPlateauStartMs >= 2_000)

    /**
     * Floor guess available as soon as the walk starts: drive-in depth plus what this
     * site has learned. Lets the user confirm the floor while still in the garage.
     */
    fun preview(): FloorEstimate {
        val carHpa = buf.pressureMedian(originMs, originMs + 5_000) ?: buf.pressure.lastOrNull()?.hPa
        val g = groundFix()
        return FloorEstimator.estimate(driveDh(g, carHpa), null, sites, siteKeyOf(g))
    }

    /**
     * Ground reference from the drive in (P4 rewind): the last trusted GNSS fix.
     * GNSS chips keep reporting fixes for a while after entering a ramp (dead
     * reckoning), so that fix can already be a level down; see [driveDh].
     */
    private fun groundFix(): GnssSample? =
        buf.gnss.lastOrNull { it.tMs <= originMs && it.good && it.accuracyM <= 12.0 } ?: buf.lastGoodGnss(originMs)

    /** Car height vs street level: street = highest level in the 40 s before the ground fix. */
    private fun driveDh(groundFix: GnssSample?, carHpa: Double?): Double? {
        if (groundFix == null || carHpa == null) return null
        return highestLevelHpa(groundFix.tMs - 40_000, groundFix.tMs + 2_000)?.let { Baro.dhM(it, carHpa) }
    }

    /** Pressure of the level the user is on now (since the last vertical move, at most 10 s). */
    private fun currentPlateauHpa(tMs: Long): Double? {
        val since = maxOf(tMs - 10_000, segmenter.lastPlateauStartMs)
        return buf.pressureMedian(since, tMs) ?: buf.pressureMedian(tMs - 3_000, tMs)
    }

    /** Highest level (lowest pressure) in a window, from 3-second medians. */
    private fun highestLevelHpa(t0: Long, t1: Long): Double? {
        val bins = buf.pressure.filter { it.tMs in t0..t1 }
            .groupBy { (it.tMs - t0) / 3_000 }
            .values.filter { it.size >= 2 }
            .map { b -> median(b.map { it.hPa }) }
        return bins.minOrNull()
    }
    val segmentsSoFar: List<VerticalSegment> get() = segmenter.segments

    fun finish(tMs: Long, end: WalkEnd): ParkingRecord {
        val carHpa = buf.pressureMedian(originMs, originMs + 5_000)
            ?: buf.pressure.firstOrNull { it.tMs >= originMs }?.hPa

        val endHpa = when (end) {
            WalkEnd.STATIONARY -> buf.pressureMedian(lastStepMs + 5_000, lastStepMs + 120_000)
                ?: currentPlateauHpa(tMs)
            WalkEnd.GNSS_OUTDOOR -> buf.pressureMedian(gnssGoodSinceMs ?: (tMs - 10_000), tMs)
            else -> currentPlateauHpa(tMs)
        }
        val dhEnd = if (carHpa != null && endHpa != null) Baro.dhM(carHpa, endHpa) else null

        val groundFix = groundFix()
        val dhDrive = driveDh(groundFix, carHpa)
        val dhWalk = if (end == WalkEnd.GNSS_OUTDOOR && dhEnd != null) -dhEnd else null

        val endFix = buf.gnss.lastOrNull { it.good && it.tMs >= originMs }
        val siteKey = siteKeyOf(groundFix ?: endFix)
        val floor = FloorEstimator.estimate(dhDrive, dhWalk, sites, siteKey)

        // Horizontal path on the car floor: from the car to the anchor.
        val anchorIdx = anchorTrackIdx
        val carFloorTrack = if (anchorIdx != null) pdr.track.subList(0, anchorIdx) else pdr.track.toList()
        val grid = PathTools.gridAngle(carFloorTrack)
        val pts = PathTools.rebuild(carFloorTrack, grid)
        var carInAnchor: Vec2? = null
        var waypoints: List<Vec2> = emptyList()
        var doorBearing: Double? = null
        if (anchorIdx != null && pts.isNotEmpty()) {
            val anchor = pts.last()
            doorBearing = doorBearing(listOf(Vec2.ZERO) + pts)
            val start = PathTools.foldStart(pts, pts.lastIndex)
            val walked = listOf(Vec2.ZERO) + pts.subList(maxOf(start, 0), pts.size)
            val simple = PathTools.simplify(walked)
            // Express in the anchor frame and reverse: anchor -> ... -> car.
            val rev = simple.reversed().map { it - anchor }
            waypoints = rev.drop(1)
            carInAnchor = -anchor
        } else if (anchorIdx != null) {
            carInAnchor = Vec2.ZERO
            waypoints = listOf(Vec2.ZERO)
        }

        return ParkingRecord(
            parkedAtWallMs = parkedAtWallMs,
            siteKey = siteKey,
            carPressureHpa = carHpa,
            dhGroundDriveM = dhDrive,
            dhGroundWalkM = dhWalk,
            dhEndM = dhEnd,
            endKind = end,
            anchorMode = anchorMode,
            carInAnchorFrame = carInAnchor,
            waypointsFromAnchor = waypoints,
            gridAngleDeg = grid,
            pathLengthM = carFloorTrack.sumOf { it.lenM },
            segments = segmenter.segments.toList(),
            floor = floor,
            doorBearingDeg = doorBearing,
            carGeo = carGeo(),
        )
    }

    /** Bearing from the end of [path] back to the point ~3 m earlier along it. */
    private fun doorBearing(path: List<Vec2>, backM: Double = 3.0): Double? {
        if (path.size < 2) return null
        val end = path.last()
        var walked = 0.0
        for (i in path.lastIndex downTo 1) {
            walked += (path[i] - path[i - 1]).norm()
            if (walked >= backM) return (path[i - 1] - end).bearingDeg()
        }
        val start = path.first() - end
        return if (start.norm() >= 1.0) start.bearingDeg() else null
    }

    /** Open-air parking: a trusted fix close to the moment of leaving the car. */
    private fun carGeo(): GeoFix? {
        val fix = buf.gnss.filter { it.good && it.tMs in (originMs - 30_000)..(originMs + 20_000) }
            .minByOrNull { abs(it.tMs - originMs) } ?: return null
        return GeoFix(fix.lat, fix.lon, fix.accuracyM)
    }

    private fun feedPressure(tMs: Long, hPa: Double) {
        latestMs = maxOf(latestMs, tMs)
        val h = Baro.altitudeM(smoother.push(hPa))
        val seg = segmenter.onHeight(tMs, h) ?: return
        // The first real vertical move after leaving the car defines the anchor.
        if (anchorTrackIdx == null && seg.startMs >= originMs && isAnchorWorthy(seg)) {
            anchorTrackIdx = pdr.track.indexOfLast { it.tMs <= seg.startMs } + 1
            anchorMode = seg.mode
        }
    }

    private fun feedStep(tMs: Long) {
        latestMs = maxOf(latestMs, tMs)
        if (tMs < originMs) return
        lastStepMs = tMs
        segmenter.onStep(tMs)
        pdr.onStep(tMs)
    }

    private fun isAnchorWorthy(s: VerticalSegment): Boolean =
        abs(s.dhM) >= 1.5 && !(s.mode == VerticalMode.RAMP && abs(s.dhM) < 2.5)

    companion object {
        /** ~200 m grid cell; good enough to tell "my apartment" from "the mall". */
        fun siteKeyOf(g: GnssSample?): String? {
            g ?: return null
            val lat = floor(g.lat / 0.002) * 0.002
            val lon = floor(g.lon / 0.002) * 0.002
            return "%.3f,%.3f".format(java.util.Locale.US, lat, lon)
        }
    }
}
