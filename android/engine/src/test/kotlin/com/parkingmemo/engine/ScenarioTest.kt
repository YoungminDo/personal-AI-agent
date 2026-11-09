package com.parkingmemo.engine

import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scenario tests named after docs/parking-finder/scenarios.md IDs.
 *
 * Reference garage: street level 1012.84 hPa, car on B3 1013.92 hPa (9.0 m down),
 * home on the 15th floor 1008.08 hPa (48.6 m above the car).
 * Walk-out: 30 m north, 15 m west, lift up. The car is 15 m east, 30 m south of the lift door.
 */
class ScenarioTest {
    private val pStreet = 1012.84
    private val pCar = 1013.92
    private val pHome = 1008.08
    private val carFromLift = Vec2(15.0, -30.0)

    /** A01: drive down the ramp, park, walk to the lift, ride up home. */
    private fun firstParkingLog(
        sim: Sim = Sim(),
        carry: Carry = Carry.HAND,
        carryOffset: Double = 0.0,
        switchToPocketAfterM: Double? = null,
    ): Sim {
        sim.event("CAR_CONNECTED")
        sim.stand(60_000)                  // driving on the street, GNSS good
        sim.gnssGood = false               // into the garage
        sim.ride(pCar, 50_000)             // spiral ramp down
        sim.stand(5_000)
        sim.event("CAR_DISCONNECTED")      // engine off
        sim.carry = carry                  // already pocketed in the car (D02) or in hand
        sim.carryOffset = carryOffset
        sim.stand(20_000)                  // sit for a bit (A05)
        if (switchToPocketAfterM != null) {
            sim.walk(switchToPocketAfterM, 0.0)
            sim.carry = Carry.POCKET       // D05: pocketed while walking
            sim.carryOffset = carryOffset + 90.0
            sim.walk(30.0 - switchToPocketAfterM, 0.0)
        } else {
            sim.walk(30.0, 0.0)
        }
        sim.walk(15.0, 270.0)
        sim.stand(8_000)                   // wait for the lift
        sim.ride(pHome, 28_000)            // lift to 15F
        sim.stand(2_000)
        sim.walk(8.0, 90.0)                // to the front door
        sim.stand(6 * 60_000L)             // at home, phone on the table
        return sim
    }

    private fun parse(sim: Sim) = LogReplay.replayParking(sim.lines.asSequence())

    /** Next morning: from home, lift down, walk east then south to the car (phone in hand). */
    private fun returnFromHome(
        rec: ParkingRecord,
        weatherHpa: Double = 2.23,
        gnssAtHome: Boolean = false,
    ): Pair<SessionFeeder, GuideSession> {
        val sim = Sim(seed = 11, pHpa = pHome, pressureOffset = weatherHpa)
        val session = GuideSession(rec)
        val feeder = SessionFeeder(session)
        sim.gnssGood = gnssAtHome
        sim.stand(6_000)
        feeder.feed(sim)
        sim.gnssGood = false
        sim.ride(pCar, 28_000)
        sim.stand(1_500)                   // doors open
        feeder.feed(sim)
        sim.walk(15.0, 90.0)
        sim.walk(26.0, 180.0)
        feeder.feed(sim)
        return feeder to session
    }

    @Test
    fun `A01 first parking records B3 and the path back from the lift`() {
        val rec = parse(firstParkingLog()).records.single()
        println("A01 record: $rec")
        assertEquals("B3", rec.floor.label)
        assertTrue(abs(rec.dhGroundDriveM!! - (-9.0)) < 0.5, "drive-in dh ${rec.dhGroundDriveM}")
        assertTrue(abs(rec.dhEndM!! - 48.6) < 0.8, "car->home dh ${rec.dhEndM}")
        assertEquals(VerticalMode.ELEVATOR, rec.anchorMode)
        assertEquals(WalkEnd.STATIONARY, rec.endKind)
        val car = rec.carInAnchorFrame!!
        assertTrue((car - carFromLift).norm() < 2.5, "car in anchor frame $car")
        assertEquals(car, rec.waypointsFromAnchor.last())
        assertTrue(rec.waypointsFromAnchor.size >= 2, "expected a corner waypoint, got ${rec.waypointsFromAnchor}")
        assertTrue(abs(angleDiff(rec.doorBearingDeg!!, 90.0)) < 15.0, "door bearing ${rec.doorBearingDeg}")
        assertNull(rec.carGeo, "underground parking must not keep a GPS position")
    }

    @Test
    fun `B01 overnight weather change does not move the floor - guided back from home`() {
        val rec = parse(firstParkingLog()).records.single()
        val (feeder, session) = returnFromHome(rec)
        val end = feeder.seen.last()
        println("B01 end state: $end, distance to car ${session.distanceToCar()}")
        assertTrue(feeder.seen.any { it.mode == GuideMode.FLOOR && it.text.contains("B3") }, "far: go to B3")
        assertTrue(feeder.seen.any { it.mode == GuideMode.FLOOR && it.floorsToGo == 2 }, "near: count floors")
        assertTrue(feeder.seen.any { it.mode == GuideMode.STEP }, "turn instruction first")
        assertEquals(GuideMode.ARRIVE, end.mode)
        assertTrue(abs(end.hRelCarM!!) < 0.6, "on the car floor despite +2.23 hPa weather: ${end.hRelCarM}")
        assertTrue(session.distanceToCar()!! < 6.0)
        assertTrue(feeder.seen.any { it.mode == GuideMode.VECTOR }, "should reach the last leg")
    }

    @Test
    fun `D02 phone in the pocket on the way out, in the hand on the way back`() {
        // Pocketed from the start: every walk-out bearing is off by 90 deg.
        val rec = parse(firstParkingLog(carry = Carry.POCKET, carryOffset = 90.0)).records.single()
        val (feeder, session) = returnFromHome(rec)
        val end = feeder.seen.last()
        println("D02 end state: $end, distance to car ${session.distanceToCar()}")
        val align = feeder.seen.mapNotNull { it.alignDeg }.lastOrNull()
        assertNotNull(align, "door alignment should have run")
        assertTrue(abs(angleDiff(align, -90.0)) < 15.0, "alignment $align")
        assertEquals(GuideMode.ARRIVE, end.mode)
        assertTrue(session.distanceToCar()!! < 6.0, "distance ${session.distanceToCar()}")
    }

    @Test
    fun `D05 pocketing the phone mid-walk keeps the path shape`() {
        val rec = parse(firstParkingLog(switchToPocketAfterM = 10.0)).records.single()
        val car = rec.carInAnchorFrame!!
        println("D05 car in anchor frame: $car")
        assertTrue((car - carFromLift).norm() < 3.5, "car in anchor frame $car")
    }

    @Test
    fun `leg swing in a pocket is not mistaken for moving the phone`() {
        val tr = HeadingTracker()
        var t = 0L
        repeat(300) {
            t += 100
            val th = Math.toRadians(20.0 * sin(2 * PI * 0.9 * t / 1000.0))
            tr.onSample(t, 135.0, doubleArrayOf(0.0, kotlin.math.cos(th), sin(th)))
        }
        assertEquals(0, tr.switches)
        assertEquals(135.0, tr.heading(t)!!, 1.0)
    }

    @Test
    fun `start ambiguity - a GPS fix at the home window does not mislead the floor count`() {
        val rec = parse(firstParkingLog()).records.single()
        val (feeder, session) = returnFromHome(rec, gnssAtHome = true)
        val end = feeder.seen.last()
        assertTrue(session.switches >= 1, "should have tried ground then corrected")
        assertEquals("도착지 출발", end.ref)
        assertEquals(GuideMode.ARRIVE, end.mode)
    }

    @Test
    fun `start ambiguity - walking down from the street without GPS picks the street start`() {
        val rec = parse(firstParkingLog()).records.single()
        val sim = Sim(seed = 21, pHpa = pStreet, pressureOffset = -1.5)
        sim.gnssGood = false
        val session = GuideSession(rec)
        val feeder = SessionFeeder(session)
        sim.stand(6_000)
        sim.walk(60.0, 180.0, pTo = pCar)  // down the car ramp on foot
        sim.stand(5_000)
        feeder.feed(sim)
        val s = feeder.seen.last()
        assertEquals("지상 출발", s.ref)
        assertTrue(abs(s.hRelCarM!!) < 0.6, "should be on the car floor: $s")
    }

    @Test
    fun `E05 getting off one floor early warns to go one more floor down`() {
        val rec = parse(firstParkingLog()).records.single()
        val sim = Sim(seed = 3, pHpa = pHome)
        sim.gnssGood = false
        val feeder = SessionFeeder(GuideSession(rec))
        sim.stand(3_000)
        sim.ride(pCar - 0.36, 26_000)       // stops on B2 (3 m above the car)
        sim.stand(5_000)
        feeder.feed(sim)
        val s = feeder.seen.last()
        assertEquals(GuideMode.FLOOR, s.mode)
        assertEquals(1, s.floorsToGo)
        assertTrue(s.text.contains("1개 층 더 내려가세요"), s.text)
    }

    @Test
    fun `F05 open-air parking guides with GPS`() {
        val sim = Sim(seed = 5)
        sim.event("CAR_CONNECTED")
        sim.stand(30_000)
        sim.event("CAR_DISCONNECTED")
        sim.stand(3_000)
        sim.walk(40.0, 0.0)                // walk away across the lot
        sim.stand(40_000)                  // outdoors, GNSS good -> walk-out ends
        val rec = parse(sim).records.single()
        assertEquals(WalkEnd.GNSS_OUTDOOR, rec.endKind)
        assertNotNull(rec.carGeo)
        assertNull(rec.anchorMode)

        val back = Sim(seed = 6)
        back.pos = Vec2(0.0, 40.0)         // 40 m north of the car
        val feeder = SessionFeeder(GuideSession(rec))
        back.stand(3_000)
        feeder.feed(back)
        val far = feeder.seen.last()
        assertEquals(GuideMode.VECTOR, far.mode)
        assertTrue(abs(far.distanceM!! - 40.0) < 3.0, "distance ${far.distanceM}")
        back.walk(37.0, 180.0)
        back.stand(2_000)
        feeder.feed(back)
        assertEquals(GuideMode.ARRIVE, feeder.seen.last().mode)
    }

    @Test
    fun `B03 a fire-door pressure spike is not a floor change`() {
        val seg = VerticalSegmenter()
        val sm = PressureSmoother()
        val rnd = java.util.Random(5)
        var t = 0L
        repeat(300) {
            t += 100
            val spike = if (t in 15_000L..15_800L) 0.4 else 0.0
            seg.onHeight(t, Baro.altitudeM(sm.push(pCar + spike + rnd.nextGaussian() * 0.012)))
        }
        assertTrue(seg.segments.isEmpty(), "spike created segments: ${seg.segments}")
    }

    @Test
    fun `A07 a Bluetooth glitch while driving does not record a parking`() {
        val sim = Sim()
        sim.event("CAR_CONNECTED")
        sim.stand(30_000)
        sim.event("CAR_DISCONNECTED")
        sim.stand(10_000)
        sim.event("CAR_CONNECTED")          // reconnects before any walking
        sim.stand(30_000)
        val res = parse(sim)
        assertTrue(res.records.isEmpty())
        assertTrue(res.outputs.none { it is ParkingPipeline.Output.WalkStarted })
    }

    @Test
    fun `A06 walking away with the engine running rewinds the origin`() {
        val sim = Sim()
        sim.event("CAR_CONNECTED")
        sim.stand(30_000)
        sim.gnssGood = false
        sim.ride(pCar, 40_000)
        val walkStart = sim.t
        sim.walk(6.0, 0.0)                  // already walking...
        sim.event("CAR_DISCONNECTED")       // ...when Bluetooth drops ~6 m away
        sim.walk(4.0, 0.0)
        val res = parse(sim)
        val started = res.outputs.filterIsInstance<ParkingPipeline.Output.WalkStarted>().single()
        assertTrue(started.originMs - walkStart in 0..1_200, "origin ${started.originMs} vs walk start $walkStart")
    }

    @Test
    fun `A05 sitting in the car before leaving keeps the car as the origin`() {
        val rec = parse(firstParkingLog()).records.single()
        assertTrue(abs(rec.carInAnchorFrame!!.norm() - 33.5) < 2.5)
    }

    @Test
    fun `D10 a user correction relabels the level for the next parking`() {
        val sites = SiteBook()
        val rec1 = LogReplay.replayParking(firstParkingLog().lines.asSequence(), sites).records.single()
        sites.addLabel(rec1.siteKey!!, rec1.floor.dhGroundM!!, "B2")
        val rec2 = LogReplay.replayParking(firstParkingLog(Sim(seed = 99)).lines.asSequence(), sites).records.single()
        assertEquals("B2", rec2.floor.label)
        assertTrue(rec2.floor.basis.contains("학습"))
    }

    @Test
    fun `replayGuide samples one state per second`() {
        val rec = parse(firstParkingLog()).records.single()
        val sim = Sim(seed = 2, pHpa = pHome)
        sim.gnssGood = false
        sim.stand(10_000)
        val states = LogReplay.replayGuide(sim.lines.asSequence(), rec)
        assertTrue(states.size in 9..11, "states ${states.size}")
        assertEquals(GuideMode.FLOOR, states.last().second.mode)
    }

    @Test
    fun `guidance pulse interval follows the design formula`() {
        assertEquals(1.9, Guidance.beepInterval(30.0, 2.5), 1e-9)
        assertEquals(0.7, Guidance.beepInterval(10.0, 2.5), 1e-9)
        assertEquals(0.15, Guidance.beepInterval(0.5, 0.1), 1e-9)
        assertEquals(1.5, Guidance.beepInterval(5.0, 5.0), 1e-9) // too uncertain: search pulse
    }

    @Test
    fun `cutting the corner still advances to the next leg`() {
        val wp = Vec2(15.0, 0.0)
        val car = Vec2(15.0, -30.0)
        assertTrue(Guidance.passedWaypoint(Vec2(11.0, -4.0), wp, car))
        assertTrue(!Guidance.passedWaypoint(Vec2(8.0, 0.0), wp, car))
    }

    @Test
    fun `labels and floor height`() {
        assertEquals("B3", FloorEstimator.labelFor(-9.0, 3.0))
        assertEquals("1F", FloorEstimator.labelFor(-1.2, 3.0))
        assertEquals("3F", FloorEstimator.labelFor(6.2, 3.0))
        val sites = SiteBook()
        sites.addLabel("s", -4.2, "B1")
        sites.addLabel("s", -8.4, "B2")
        assertEquals(4.2, sites.floorHeight("s"), 1e-9)
        assertNull(sites.labelFor("other", -4.2))
    }

    @Test
    fun `forward bearing for flat and upright phones`() {
        val flatNorth = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        assertEquals(0.0, Heading.forwardBearing(flatNorth)!!, 1e-6)
        // Upright, screen facing the user who faces east: device y = up, device z = west.
        val uprightEast = doubleArrayOf(
            0.0, 0.0, -1.0,
            -1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
        )
        assertEquals(90.0, Heading.forwardBearing(uprightEast)!!, 1e-6)
    }

    @Test
    fun `vector rotation and GPS offsets`() {
        val v = Vec2(0.0, 10.0).rotatedBy(90.0)
        assertEquals(10.0, v.x, 1e-9)
        assertEquals(0.0, v.y, 1e-9)
        val a = GeoFix(37.5, 127.0, 5.0)
        val b = GeoFix(37.5 + 30.0 / 110_540.0, 127.0, 5.0)
        assertEquals(30.0, a.vectorTo(b).norm(), 0.01)
    }
}
