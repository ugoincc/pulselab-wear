package br.unioeste.pulselab.wear.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `batch timestamps are spread backwards from the batch end`() {
        // Three 600 ms intervals stamped at t=10000: the last ends at the stamp,
        // each earlier one 600 ms sooner.
        val times = ibiEndTimes(listOf(600, 600, 600), batchEndMs = 10_000L)
        assertEquals(8_800L, times[0])
        assertEquals(9_400L, times[1])
        assertEquals(10_000L, times[2])
    }

    @Test
    fun `a long batch spans more than the window it lands in`() {
        // The device case: one data point carrying ~165 intervals covers roughly
        // two minutes, so it must NOT all fit inside a 60 s window.
        val intervals = List(165) { 700 }
        val times = ibiEndTimes(intervals, batchEndMs = 200_000L)
        val spanMs = times.last() - times.first()
        assertTrue("batch should span well over a minute, was $spanMs ms", spanMs > 60_000L)

        val window = IbiWindow(windowMs = 60_000L)
        intervals.forEachIndexed { i, ibi -> window.add(ibi.toDouble(), times[i]) }
        assertTrue("window should hold ~60 s worth, not the whole batch", window.size < intervals.size)
    }

    @Test
    fun `window rejects physiologically impossible intervals`() {
        val window = IbiWindow()
        assertTrue(window.add(800.0, atMs = 0L))
        assertFalse("2500 ms would be 24 bpm", window.add(2_500.0, atMs = 100L))
        assertFalse("200 ms would be 300 bpm", window.add(200.0, atMs = 200L))
        assertEquals(1, window.size)
        assertEquals(2, window.rejected)
    }

    @Test
    fun `window rejects a missed beat reported as one long interval`() {
        // The exact failure seen on device: a doubled interval next to normal ones
        // drove RMSSD to ~300 ms at a resting heart rate of 100 bpm.
        val window = IbiWindow()
        window.add(600.0, atMs = 0L)
        window.add(610.0, atMs = 600L)
        assertFalse("1300 ms is a missed beat, not a real interval", window.add(1_300.0, atMs = 1_210L))
        window.add(605.0, atMs = 1_810L)

        // Without the filter this window would read well above 300 ms.
        assertTrue("RMSSD should stay physiological, was ${window.currentRmssd()}", window.currentRmssd()!! < 30.0)
    }

    @Test
    fun `window re-anchors after a sustained genuine change`() {
        // Standing up or re-seating the watch shifts the heart rate for real. The
        // Malik rule must not lock the window out forever.
        val window = IbiWindow()
        window.add(1_000.0, atMs = 0L)

        var atMs = 100L
        var accepted = 0
        repeat(IbiWindow.MAX_CONSECUTIVE_REJECTIONS + 1) {
            if (window.add(600.0, atMs)) accepted++
            atMs += 100L
        }

        assertTrue("window should eventually accept the new level", accepted > 0)
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
