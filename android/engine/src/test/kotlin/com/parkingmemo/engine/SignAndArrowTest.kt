package com.parkingmemo.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignAndArrowTest {
    @Test
    fun `reads floor and pillar from typical garage signs`() {
        // Field test 3 photo: pillars "B4 / 나4", "B4 / 나2", "B4 / 나1"; the nearest is largest.
        val photo = listOf("B4" to 900, "나4" to 900, "B4" to 400, "나2" to 400, "B4" to 200, "나1" to 200)
        assertEquals(SignInfo("B4", "나4"), SignReader.read(photo))
        assertEquals(SignInfo("B4", null), SignReader.read(listOf("B4 주차구역 안내" to 500)))
        assertEquals(SignInfo("B3", null), SignReader.read(listOf("지하 3층" to 500)))
        assertEquals(SignInfo("B2", "C-17"), SignReader.read(listOf("P2" to 300, "C-17" to 600)))
        assertEquals("C-17", SignReader.pillarIn("C17"))
        assertNull(SignReader.pillarIn("B4"), "a floor label is not a pillar code")
        assertNull(SignReader.pillarIn("지하 4층"))
    }

    @Test
    fun `no arrow before the direction is aligned`() {
        val rec = ParkingRecord(
            parkedAtWallMs = 0, siteKey = null, carPressureHpa = 1013.92, dhGroundDriveM = null,
            dhGroundWalkM = null, dhEndM = 9.0, endKind = WalkEnd.STATIONARY, anchorMode = VerticalMode.ELEVATOR,
            carInAnchorFrame = Vec2(15.0, -30.0), waypointsFromAnchor = listOf(Vec2(15.0, 0.0), Vec2(15.0, -30.0)),
            gridAngleDeg = 0.0, pathLengthM = 45.0, segments = emptyList(), floor = FloorEstimate("B3", -9.0, 0.9, "t"),
            doorBearingDeg = 90.0,
        )
        val sim = Sim(seed = 71, pHpa = 1013.92 - 1.08)
        sim.gnssGood = false
        val feeder = SessionFeeder(GuideSession(rec))
        sim.stand(3_000)
        sim.ride(1013.92, 15_000)
        sim.stand(1_500)
        sim.walk(8.0, 90.0)
        feeder.feed(sim)
        val onFloor = feeder.seen.filter { it.mode == GuideMode.STEP || it.mode == GuideMode.VECTOR }
        val first = onFloor.first()
        assertNull(first.arrowDeg, "arrow hidden until aligned: $first")
        assertTrue(first.text.contains("방향을 맞추는 중"), first.text)
        val last = onFloor.last()
        assertTrue(last.arrowDeg != null, "arrow shown once aligned: $last")
    }
}
