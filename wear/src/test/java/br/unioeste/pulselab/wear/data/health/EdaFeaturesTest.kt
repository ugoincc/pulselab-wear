package br.unioeste.pulselab.wear.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdaFeaturesTest {

    @Test
    fun `tonic level is null while the window is empty`() {
        assertNull(EdaWindow().tonicLevel())
    }

    @Test
    fun `tonic level averages the window`() {
        val window = EdaWindow()
        window.add(4.0, atMs = 0L)
        window.add(6.0, atMs = 1_000L)
        assertEquals(5.0, window.tonicLevel()!!, 1e-9)
    }

    @Test
    fun `window evicts samples older than its length`() {
        val window = EdaWindow(windowMs = 1_000L)
        window.add(1.0, atMs = 0L)
        window.add(2.0, atMs = 500L)
        window.add(3.0, atMs = 1_200L) // evicts the t=0 sample (cutoff = 200)
        assertEquals(2, window.size)
    }

    @Test
    fun `a flat signal produces no responses`() {
        val window = EdaWindow()
        repeat(30) { window.add(5.0, atMs = it * 1_000L) }
        assertEquals(0, window.scrPerMinute())
    }

    @Test
    fun `peaks above threshold are counted as responses`() {
        val window = EdaWindow()
        // Flat baseline with three clear deflections well above the 0.02 uS floor.
        val values = MutableList(60) { 5.0 }
        values[10] = 6.0
        values[30] = 6.0
        values[50] = 6.0
        values.forEachIndexed { i, v -> window.add(v, atMs = i * 1_000L) }

        val rate = window.scrPerMinute()!!
        // 59 s of samples, so the per-minute rate lands at or just above 3.
        assertTrue("expected ~3 responses per minute, got $rate", rate >= 3)
    }

    @Test
    fun `noise below the amplitude threshold is ignored`() {
        val window = EdaWindow()
        // Deflections of 0.005 uS — a quarter of the threshold, so pure noise.
        val values = List(60) { if (it % 10 == 0) 5.005 else 5.0 }
        values.forEachIndexed { i, v -> window.add(v, atMs = i * 1_000L) }

        assertEquals(0, window.scrPerMinute())
    }
}
