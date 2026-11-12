package com.parkingmemo.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Field report: "one floor above the car, the app says I'm on the car's floor".
 * Each test reproduces one way the height relative to the car can come out short.
 */
class FloorBugTest {
    private val pStreet = 1012.84
    private val pCar = 1013.92      // B3, 9.0 m below the street

    @Test
    fun `tapping I've arrived right after the lift stops still measures the arrival height`() {
        val sim = Sim(seed = 31)
        sim.gnssGood = false
        sim.pHpa = pCar
        sim.stand(3_000)
        sim.event("MANUAL_PARK")
        sim.stand(2_000)
        sim.walk(12.0, 0.0)
        sim.stand(5_000)
        sim.ride(pStreet, 18_000)          // lift up to 1F (+9.0 m)
        sim.stand(1_000)                   // doors open...
        sim.event("END_WALK")              // ...and the user taps right away
        sim.stand(15_000)
        val rec = LogReplay.replayParking(sim.lines.asSequence()).records.single()
        println("arrival dhEnd=${rec.dhEndM}")
        assertTrue(abs(rec.dhEndM!! - 9.0) < 0.5, "arrival height ${rec.dhEndM} should be ~9.0 m")
    }

    @Test
    fun `GPS fixes that linger into the ramp do not make the car floor shallower`() {
        val sim = Sim(seed = 32)
        sim.event("CAR_CONNECTED")
        sim.stand(60_000)
        // Into the ramp: the GNSS chip keeps reporting fixes for 20 s (dead reckoning).
        sim.gnssAccuracy = 9.0
        sim.ride(pStreet + 0.43, 20_000)  // first ~3.6 m of descent with "good" fixes
        sim.gnssGood = false
        sim.ride(pCar, 30_000)
        sim.stand(5_000)
        sim.event("CAR_DISCONNECTED")
        sim.stand(5_000)
        sim.walk(30.0, 0.0)
        sim.stand(8_000)
        sim.ride(1008.08, 28_000)
        sim.walk(5.0, 90.0)
        sim.stand(6 * 60_000L)
        val rec = LogReplay.replayParking(sim.lines.asSequence()).records.single()
        println("lingering GPS: dhGround=${rec.floor.dhGroundM} label=${rec.floor.label}")
        assertEquals("B3", rec.floor.label)
        assertTrue(abs(rec.floor.dhGroundM!! + 9.0) < 0.6, "ground dh ${rec.floor.dhGroundM}")
    }

    @Test
    fun `one floor above with a 1_4 m error is still one floor above`() {
        // Car floor reference with a 1.4 m error: user is 3.0 m above, the app thinks 1.6 m.
        val rec = ParkingRecord(
            parkedAtWallMs = 0, siteKey = null, carPressureHpa = pCar, dhGroundDriveM = null,
            dhGroundWalkM = null, dhEndM = 1.6, endKind = WalkEnd.MANUAL, anchorMode = VerticalMode.STAIRS,
            carInAnchorFrame = Vec2(0.0, -10.0), waypointsFromAnchor = listOf(Vec2(0.0, -10.0)),
            gridAngleDeg = null, pathLengthM = 10.0, segments = emptyList(),
            floor = FloorEstimate("B3", null, 0.7, "test"),
        )
        val g = Guidance(rec, Guidance.StartRef.WALK_END)
        var t = 0L
        repeat(60) { t += 100; g.onPressure(t, pCar - 0.36) }
        val s = g.state(t)
        assertEquals(GuideMode.FLOOR, s.mode, "1.6 m above should not read as the car floor: $s")
    }

    @Test
    fun `telling the app the current floor fixes a wrong reference`() {
        // Stored reference is 3.5 m off (the field report): at the car floor the app said "go up one".
        val rec = ParkingRecord(
            parkedAtWallMs = 0, siteKey = null, carPressureHpa = pCar, dhGroundDriveM = null,
            dhGroundWalkM = -13.1, dhEndM = 13.1, endKind = WalkEnd.GNSS_OUTDOOR, anchorMode = null,
            carInAnchorFrame = null, waypointsFromAnchor = emptyList(), gridAngleDeg = null, pathLengthM = 0.0,
            segments = emptyList(), floor = FloorEstimate("B4", -13.1, 0.6, "test"),
        )
        val sim = Sim(seed = 41, pHpa = pCar - 2.0)       // starts 16.6 m above the car
        sim.gnssGood = false
        val session = GuideSession(rec)
        val feeder = SessionFeeder(session)
        sim.stand(3_000)
        sim.ride(pCar - 0.36, 20_000)                     // one floor above the car
        sim.stand(4_000)
        feeder.feed(sim)
        assertEquals(GuideMode.SETTLING, feeder.seen.last().mode, "reproduces the wrong 'this floor'")
        assertTrue(session.calibrate(currentLabel = "B3", carLabel = "B4"))
        sim.stand(1_000)
        feeder.feed(sim)
        val s = feeder.seen.last()
        assertEquals(GuideMode.FLOOR, s.mode)
        assertEquals(1, s.floorsToGo)
        assertEquals("층 직접 지정", s.ref)
        sim.ride(pCar, 8_000)                             // one more floor down
        sim.stand(3_000)
        feeder.feed(sim)
        val after = feeder.seen.last()
        assertTrue(abs(after.hRelCarM!!) < 0.6, "on the car floor after calibrating: $after")
    }

    @Test
    fun `stepping off the lift asks which floor this is, with a guess, once`() {
        // Field test 2 (SFC): the stored reference was off by several metres.
        val rec = ParkingRecord(
            parkedAtWallMs = 0, siteKey = null, carPressureHpa = pCar, dhGroundDriveM = null,
            dhGroundWalkM = null, dhEndM = 20.0, endKind = WalkEnd.STATIONARY, anchorMode = VerticalMode.ELEVATOR,
            carInAnchorFrame = Vec2(10.0, 0.0), waypointsFromAnchor = listOf(Vec2(10.0, 0.0)), gridAngleDeg = null,
            pathLengthM = 10.0, segments = emptyList(), floor = FloorEstimate("B5", -16.0, 0.7, "test"),
        )
        val sim = Sim(seed = 51, pHpa = pCar - 2.4)       // started 20 m above the car per the record...
        sim.gnssGood = false
        val session = GuideSession(rec)
        val feeder = SessionFeeder(session)
        sim.stand(3_000)
        sim.ride(pCar - 1.3, 20_000)                      // ...but the lift stops ~11 m above the car (B4 in reality)
        sim.stand(2_000)
        feeder.feed(sim)
        assertTrue(feeder.seen.none { it.askFloor }, "no question while riding or standing in the lift")
        sim.walk(3.0, 90.0)                               // steps out
        feeder.feed(sim)
        val ask = feeder.seen.last()
        assertTrue(ask.askFloor, "should ask after stepping out: $ask")
        assertTrue(ask.guessLabel != null)
        assertTrue(session.calibrate(currentLabel = "B4", carLabel = "B5"))
        sim.stand(1_000)
        feeder.feed(sim)
        val after = feeder.seen.last()
        assertTrue(!after.askFloor, "asked once")
        assertEquals(1, after.floorsToGo, "now one floor down to B5: $after")
        assertTrue(after.text.contains("1개 층"), after.text)
    }

    @Test
    fun `korean particle follows how the floor is read`() {
        assertEquals("B4로", ro("B4"))
        assertEquals("B3으로", ro("B3"))
        assertEquals("B6으로", ro("B6"))
        assertEquals("B1로", ro("B1"))
        assertEquals("B7로", ro("B7"))
        assertEquals("2F로", ro("2F"))
        assertEquals("차 있는 층으로", ro("차 있는 층"))
        assertEquals("B4", SiteBook.shiftLabel("B5", 1))
        assertEquals("1F", SiteBook.shiftLabel("B1", 1))
    }

    @Test
    fun `the floor can be confirmed as soon as the walk starts, and the site remembers it`() {
        fun parkingUntilWalkStarts(sim: Sim, sites: SiteBook): ParkingPipeline {
            var t = 0L
            val p = ParkingPipeline(sites) { t }
            sim.event("CAR_CONNECTED"); sim.stand(60_000)
            sim.gnssGood = false; sim.ride(pCar, 50_000); sim.stand(5_000)
            sim.event("CAR_DISCONNECTED"); sim.walk(4.0, 0.0)
            for (line in sim.lines) {
                val f = line.split(',')
                t = f[1].toLong()
                when (f[0]) {
                    "P" -> p.pressure(t, f[2].toDouble())
                    "S" -> p.step(t)
                    "G" -> p.gnss(GnssSample(t, f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5].toInt()))
                    "E" -> if (f[2] == "CAR_CONNECTED") p.carConnected(t) else if (f[2] == "CAR_DISCONNECTED") p.carDisconnected(t)
                }
            }
            return p
        }
        val sites = SiteBook()
        val first = parkingUntilWalkStarts(Sim(seed = 61), sites).walkProgress!!.preview()
        assertEquals("B3", first.label)
        // The user answers "B5" (tall retail basements above the garage): the site learns it.
        sites.addLabel(WalkOutBuilder.siteKeyOf(GnssSample(0, 37.5123, 127.0581, 5.0, 12))!!, first.dhGroundM!!, "B5")
        val second = parkingUntilWalkStarts(Sim(seed = 62), sites).walkProgress!!.preview()
        assertEquals("B5", second.label, "next time at the same site: ${second.basis}")
    }
}
