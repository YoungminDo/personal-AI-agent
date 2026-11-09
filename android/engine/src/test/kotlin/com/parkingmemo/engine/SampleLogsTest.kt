package com.parkingmemo.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Writes sample logs (build/sample-logs) that the replay CLI can be pointed at. */
class SampleLogsTest {
    @Test
    fun `write sample parking and guide logs`() {
        val dir = File("build/sample-logs").apply { mkdirs() }
        val park = Sim()
        park.event("CAR_CONNECTED"); park.stand(60_000)
        park.gnssGood = false; park.ride(1013.92, 50_000); park.stand(5_000)
        park.event("CAR_DISCONNECTED"); park.stand(10_000)
        park.walk(30.0, 0.0); park.walk(15.0, 270.0); park.stand(8_000)
        park.ride(1008.08, 28_000); park.walk(8.0, 90.0); park.stand(6 * 60_000L)
        File(dir, "park.csv").writeText(park.lines.joinToString("\n"))

        val guide = Sim(seed = 11, pHpa = 1008.08, pressureOffset = 2.0)
        guide.gnssGood = false
        guide.stand(5_000); guide.ride(1013.92, 28_000); guide.stand(1_500)
        guide.walk(15.0, 90.0); guide.walk(28.0, 180.0)
        File(dir, "guide.csv").writeText(guide.lines.joinToString("\n"))
        assertTrue(File(dir, "park.csv").length() > 0)
    }
}
