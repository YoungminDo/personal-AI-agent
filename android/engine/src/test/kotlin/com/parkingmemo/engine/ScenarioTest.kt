package com.parkingmemo.engine

import kotlin.math.abs
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
 */
class ScenarioTest {
    private val pStreet = 1012.84
    private val pCar = 1013.92
    private val pHome = 1008.08

    /** A01: drive down the ramp, park, walk 30 m north then 15 m west, lift up home. */
    private fun firstParkingLog(sim: Sim = Sim()): Sim {
        sim.event("CAR_CONNECTED")
        sim.stand(60_000)                  // driving on the street, GNSS good
        sim.gnssGood = false               // into the garage
        sim.ride(pCar, 50_000)             // spiral ramp down
        sim.stand(5_000)
        sim.event("CAR_DISCONNECTED")      // engine off
        sim.stand(20_000)                  // sit for a bit (A05)
        sim.walk(30.0, 0.0)
        sim.walk(15.0, 270.0)
        sim.stand(8_000)                   // wait for the lift
        sim.ride(pHome, 28_000)            // lift to 15F
        sim.stand(2_000)
        sim.walk(8.0, 90.0)                // to the front door
        sim.stand(6 * 60_000L)             // at home, phone on the table
        return sim
    }

    private fun parse(sim: Sim) = LogReplay.replayParking(sim.lines.asSequence())

    @Test
    fun `A01 first parking records B3 and the path back from the lift`() {
        val res = parse(firstParkingLog())
        val rec = res.records.single()
        println("A01 record: $rec")

        assertEquals("B3", rec.floor.label)
        assertTrue(abs(rec.dhGroundDriveM!! - (-9.0)) < 0.5, "drive-in dh ${rec.dhGroundDriveM}")
        assertTrue(abs(rec.dhEndM!! - 48.6) < 0.8, "car->home dh ${rec.dhEndM}")
        assertEquals(VerticalMode.ELEVATOR, rec.anchorMode)
        assertEquals(WalkEnd.STATIONARY, rec.endKind)

        // Car relative to the lift door: 15 m east, 30 m south.
        val car = rec.carInAnchorFrame!!
        assertTrue((car - Vec2(15.0, -30.0)).norm() < 2.5, "car in anchor frame $car")
        assertEquals(car, rec.waypointsFromAnchor.last())
        assertTrue(rec.waypointsFromAnchor.size >= 2, "expected a corner waypoint, got ${rec.waypointsFromAnchor}")
    }

    @Test
    fun `B01 overnight weather change does not move the floor - guided back from home`() {
        val rec = parse(firstParkingLog()).records.single()

        // Next morning the pressure is +2.23 hPa everywhere (about 6 floors if compared directly).
        val sim = Sim(seed = 11, pHpa = pHome, pressureOffset = 2.23)
        val g = Guidance(rec, Guidance.StartRef.WALK_END)
        val seen = mutableListOf<GuideState>()
        fun feed(from: Int) {
            for (line in sim.lines.drop(from)) {
                val f = line.split(',')
                val t = f[1].toLong()
                when (f[0]) {
                    "P" -> g.onPressure(t, f[2].toDouble())
                    "S" -> g.onStep(t)
                    "H" -> g.onHeading(f[2].toDouble())
                }
                if (f[0] == "P" && t % 1000 == 0L) seen += g.state(t)
            }
        }

        sim.gnssGood = false
        sim.stand(3_000)
        feed(0)
        val atHome = g.state(sim.t)
        assertEquals(GuideMode.FLOOR, atHome.mode)
        assertTrue(atHome.text.contains("B3"), atHome.text)

        var mark = sim.lines.size
        sim.ride(pCar, 28_000)              // lift down to B3
        sim.stand(1_500)                    // doors open
        feed(mark)
        assertTrue(seen.any { it.mode == GuideMode.FLOOR && it.floorsToGo == 2 }, "should count down floors near the garage")
        assertTrue(abs(g.hRelCar()!!) < 0.6, "on the car floor despite weather: ${g.hRelCar()}")

        mark = sim.lines.size
        sim.walk(15.0, 90.0)                // walks straight out: retrace east...
        feed(mark)
        assertTrue(g.isAnchored, "should anchor at the lift door on B3, hRel=${g.hRelCar()}")
        mark = sim.lines.size
        sim.walk(26.0, 180.0)               // ...then south toward the car
        feed(mark)
        val end = g.state(sim.t)
        println("B01 end state: $end, distance to car ${g.distanceToCar()}")
        assertEquals(GuideMode.ARRIVE, end.mode)
        assertTrue(seen.any { it.mode == GuideMode.STEP }, "should give a turn instruction first")
        assertTrue(g.distanceToCar()!! < 6.0)
        assertTrue(abs(end.distanceM!! - g.distanceToCar()!!) < 0.5, "should be on the last leg: $end")
    }

    @Test
    fun `cutting the corner still advances to the next leg`() {
        val wp = Vec2(15.0, 0.0)
        val car = Vec2(15.0, -30.0)
        assertTrue(Guidance.passedWaypoint(Vec2(11.0, -4.0), wp, car))   // diagonal shortcut, 4 m off the corner
        assertTrue(!Guidance.passedWaypoint(Vec2(8.0, 0.0), wp, car))    // still on the first leg
    }

    @Test
    fun `E05 getting off one floor early warns to go one more floor down`() {
        val rec = parse(firstParkingLog()).records.single()
        val sim = Sim(seed = 3, pHpa = pHome)
        val g = Guidance(rec, Guidance.StartRef.WALK_END)
        sim.gnssGood = false
        sim.stand(3_000)
        sim.ride(pCar - 0.36, 26_000)       // stops on B2 (3 m above the car)
        sim.stand(5_000)
        for (line in sim.lines) {
            val f = line.split(',')
            if (f[0] == "P") g.onPressure(f[1].toLong(), f[2].toDouble())
        }
        val s = g.state(sim.t)
        assertEquals(GuideMode.FLOOR, s.mode)
        assertEquals(1, s.floorsToGo)
        assertTrue(s.text.contains("1개 층 더 내려가세요"), s.text)
        assertTrue(!g.isAnchored)
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
        val res = parse(firstParkingLog())
        val started = res.outputs.filterIsInstance<ParkingPipeline.Output.WalkStarted>().single()
        val rec = res.records.single()
        assertNotNull(started)
        // The first point of the reversed path is the car, roughly 33.5 m from the lift.
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
    fun `guidance pulse interval follows the design formula`() {
        assertEquals(1.9, Guidance.beepInterval(30.0, 2.5), 1e-9)
        assertEquals(0.7, Guidance.beepInterval(10.0, 2.5), 1e-9)
        assertEquals(0.15, Guidance.beepInterval(0.5, 0.1), 1e-9)
        assertEquals(1.5, Guidance.beepInterval(5.0, 5.0), 1e-9) // too uncertain: search pulse
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
}
