package br.unioeste.pulselab.wear.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HrvTest {

    @Test
    fun `rmssd is null with fewer than two intervals`() {
        assertNull(rmssd(emptyList()))
        assertNull(rmssd(listOf(800.0)))
    }

    @Test
    fun `rmssd is zero for constant intervals`() {
        assertEquals(0.0, rmssd(listOf(800.0, 800.0, 800.0))!!, 1e-9)
    }

    @Test
    fun `rmssd matches hand-computed value`() {
        // Successive diffs: +20, -40 -> sumSq = 400 + 1600 = 2000;
        // /(3 - 1) = 1000; sqrt(1000) = 31.6227766...
        assertEquals(31.6227766, rmssd(listOf(800.0, 820.0, 780.0))!!, 1e-4)
    }

    @Test
    fun `window evicts samples older than its length`() {
        val window = IbiWindow(windowMs = 1_000L)
        window.add(800.0, atMs = 0L)
        window.add(820.0, atMs = 500L)
        window.add(810.0, atMs = 1_200L) // evicts the t=0 sample (cutoff = 200)
        assertEquals(2, window.size)
    }
}
