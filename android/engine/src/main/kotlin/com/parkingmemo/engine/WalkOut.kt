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
        data class Ev(val t: Long, val kind: Int, val v: Double)
        val evs = mutableListOf<Ev>()
        pre.pressure.filter { it.tMs >= originMs - 10_000 }.forEach { evs += Ev(it.tMs, 0, it.hPa) }
        pre.headings.filter { it.tMs >= originMs - 2_000 }.forEach { evs += Ev(it.tMs, 1, it.deg) }
        pre.steps.filter { it >= originMs }.forEach { evs += Ev(it, 2, 0.0) }
        evs.sortWith(compareBy({ it.t }, { it.kind }))
        for (e in evs) when (e.kind) {
            0 -> feedPressure(e.t, e.v)
            1 -> pdr.onHeading(e.v)
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

    fun onHeading(tMs: Long, deg: Double) {
        buf.addHeading(tMs, deg)
        pdr.onHeading(deg)
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
    val segmentsSoFar: List<VerticalSegment> get() = segmenter.segments

    fun finish(tMs: Long, end: WalkEnd): ParkingRecord {
        val carHpa = buf.pressureMedian(originMs, originMs + 5_000)
            ?: buf.pressure.firstOrNull { it.tMs >= originMs }?.hPa

        val endHpa = when (end) {
            WalkEnd.STATIONARY -> buf.pressureMedian(lastStepMs + 5_000, lastStepMs + 120_000)
                ?: buf.pressureMedian(tMs - 10_000, tMs)
            WalkEnd.GNSS_OUTDOOR -> buf.pressureMedian(gnssGoodSinceMs ?: (tMs - 10_000), tMs)
            else -> buf.pressureMedian(tMs - 10_000, tMs)
        }
        val dhEnd = if (carHpa != null && endHpa != null) Baro.dhM(carHpa, endHpa) else null

        // Ground reference from the drive in: the last trusted GNSS fix (P4 rewind).
        val groundFix = buf.lastGoodGnss(originMs)
        val dhDrive = if (groundFix != null && carHpa != null) {
            buf.pressureMedian(groundFix.tMs - 2_000, groundFix.tMs + 2_000)?.let { Baro.dhM(it, carHpa) }
        } else null
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
        if (anchorIdx != null && pts.isNotEmpty()) {
            val anchor = pts.last()
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
        )
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
