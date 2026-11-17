package com.parkingmemo.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class RecordCodecTest {
    @Test
    fun `every field survives saving and loading`() {
        val r = ParkingRecord(
            parkedAtWallMs = 1_794_881_540_290, siteKey = "37.566,126.978", carPressureHpa = 1013.9207,
            dhGroundDriveM = -16.25, dhGroundWalkM = -16.6, dhEndM = 48.71, endKind = WalkEnd.GNSS_OUTDOOR,
            anchorMode = VerticalMode.ELEVATOR, carInAnchorFrame = Vec2(15.057804, -31.004931),
            waypointsFromAnchor = listOf(Vec2(15.04, -0.44), Vec2(15.057804, -31.004931)), gridAngleDeg = 89.98,
            pathLengthM = 45.776, segments = listOf(VerticalSegment(169_400, 198_000, 48.6857, VerticalMode.ELEVATOR)),
            floor = FloorEstimate("B5", -16.25, 0.83, "주행+도보 일치 + 학습된 층 이름"), userLabel = "B5",
            doorBearingDeg = 89.98, carGeo = GeoFix(37.5123, 127.0581, 5.0), pillarCode = "나4",
            photoPath = "/data/user/0/com.parkingmemo.test/files/photos/p1.jpg",
        )
        assertEquals(r, RecordCodec.decode(RecordCodec.encode(r)))
    }

    @Test
    fun `missing optional fields decode as null`() {
        val minimal = "parkedAtWallMs=1\nendKind=MANUAL\nfloor.confidence=0"
        val r = RecordCodec.decode(minimal)!!
        assertEquals(null, r.doorBearingDeg)
        assertEquals(emptyList(), r.waypointsFromAnchor)
    }
}
