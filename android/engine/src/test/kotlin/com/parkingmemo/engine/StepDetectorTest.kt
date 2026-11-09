package com.parkingmemo.engine

import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class StepDetectorTest {
    private fun run(seconds: Int, walkHz: Double?, noise: Double, seed: Long = 1): Int {
        val d = AccelStepDetector()
        val rnd = Random(seed)
        var steps = 0
        var t = 0L
        repeat(seconds * 50) {
            t += 20
            val s = t / 1000.0
            val walk = if (walkHz != null) {
                // Vertical bounce at step frequency with a second harmonic, like a real gait.
                3.0 * sin(2 * PI * walkHz * s) + 0.8 * sin(4 * PI * walkHz * s + 0.6)
            } else 0.0
            val az = 9.81 + walk + rnd.nextGaussian() * noise
            val ax = rnd.nextGaussian() * noise
            val ay = rnd.nextGaussian() * noise
            if (d.onAccel(t, ax, ay, az) != null) steps++
        }
        return steps
    }

    @Test
    fun `counts steps while walking`() {
        val n = run(20, 1.8, 0.3)
        assertTrue(n in 33..39, "expected ~36 steps, got $n")
    }

    @Test
    fun `stays quiet while standing still`() {
        val n = run(20, null, 0.15)
        assertTrue(n <= 1, "expected no steps, got $n")
    }
}
