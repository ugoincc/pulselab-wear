package br.unioeste.pulselab.wear.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

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
    fun `rmssd over runs ignores the pair that straddles a gap`() {
        // One run of 800/810 and another of 700/705. Pooled, the only differences
        // are 10 and 5 — the 110 ms step between the runs was never a beat-to-beat
        // change and must not appear.
        val pooled = rmssdOverRuns(listOf(listOf(800.0, 810.0), listOf(700.0, 705.0)))!!
        val flat = rmssd(listOf(800.0, 810.0, 700.0, 705.0))!!

        assertEquals(sqrt((10.0 * 10.0 + 5.0 * 5.0) / 2), pooled, 1e-9)
        assertTrue("flattening the gap inflates RMSSD ($flat vs $pooled)", flat > pooled * 3)
    }

    @Test
    fun `rmssd over runs is null when no run has a pair`() {
        assertNull(rmssdOverRuns(listOf(listOf(800.0), listOf(700.0))))
        assertNull(rmssdOverRuns(emptyList()))
    }

    @Test
    fun `window splits into runs at the sensor's periodic dropout`() {
        // The dropout measured on device: the heart-rate tracker returns nothing
        // valid for ~11 s, about once a minute (SENSORS.md §4.3). Beats either
        // side of it are adjacent in the list and 11 s apart in the heart.
        val window = IbiWindow(windowMs = 60_000L)
        var atMs = 0L
        repeat(3) {
            atMs += 800L
            window.add(800.0, atMs)
        }
        atMs += 11_000L // the hole
        repeat(3) {
            window.add(800.0, atMs)
            atMs += 800L
        }

        val runs = window.currentRuns()
        assertEquals("the hole should break the window in two", 2, runs.size)
        assertEquals(6, runs.sumOf { it.size })
    }

    @Test
    fun `one interval per data point still counts as one run`() {
        // How the SDK actually delivers: 247 of 261 data points carried exactly one
        // interval, stamped ~1000 ms apart while the beats are ~750 ms apart. An
        // earlier version tested succession with that arithmetic and split every
        // beat into its own run, leaving RMSSD frozen on a handful of pairs.
        val window = IbiWindow()
        var atMs = 0L
        repeat(10) {
            atMs += 1_000L
            window.add(750.0 + (it % 3) * 5, atMs)
        }

        val runs = window.currentRuns()
        assertEquals("uninterrupted delivery is a single run", 1, runs.size)
        assertEquals(10, runs.first().size)
        assertNotNull("RMSSD must exist over normal delivery", window.currentRmssd())
    }

    @Test
    fun `an interval the SDK flagged invalid breaks the run`() {
        val window = IbiWindow()
        window.add(800.0, atMs = 1_000L)
        window.add(805.0, atMs = 2_000L)
        window.noteDropped() // the SDK reported an interval with a bad status
        window.add(802.0, atMs = 3_000L)

        assertEquals(2, window.currentRuns().size)
    }

    @Test
    fun `a resumed run at a different rate does not fabricate a difference`() {
        // Heart rate drifts across an 11 s dropout — perfectly normal. Pairing the
        // last beat before with the first after would invent a 150 ms successive
        // difference and inflate RMSSD, which is squared and so dominates.
        val window = IbiWindow()
        var atMs = 0L
        listOf(800.0, 805.0, 800.0).forEach { atMs += it.toLong(); window.add(it, atMs) }
        atMs += 11_000L
        // 650 ms is a >20 % step, so the Malik rule needs a few beats to re-anchor.
        repeat(IbiWindow.MAX_CONSECUTIVE_REJECTIONS + 3) {
            atMs += 650L
            window.add(650.0, atMs)
        }

        val rmssdValue = window.currentRmssd()!!
        assertTrue(
            "no successive pair differs by more than a few ms, was $rmssdValue",
            rmssdValue < 20.0
        )
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
