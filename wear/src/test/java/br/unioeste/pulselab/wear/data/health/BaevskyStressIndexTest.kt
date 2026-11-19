package br.unioeste.pulselab.wear.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaevskyStressIndexTest {

    @Test
    fun `si is null below the minimum window`() {
        val tooShort = List(BaevskyStressIndex.MIN_INTERVALS - 1) { 800.0 }
        assertNull(BaevskyStressIndex.compute(tooShort))
    }

    @Test
    fun `si is null for a perfectly constant series`() {
        // MxDMn is zero, so the index is undefined rather than infinite — the
        // guard that keeps a flatlined sensor from emitting a garbage value.
        assertNull(BaevskyStressIndex.compute(List(60) { 800.0 }))
    }

    @Test
    fun `low variability yields a much higher index than high variability`() {
        // The defining property of SI: it rises as RR intervals concentrate, which
        // is what sympathetic dominance looks like in the histogram.
        val lowVariability = List(60) { if (it % 2 == 0) 800.0 else 802.0 }
        val highVariability = List(60) { 700.0 + (it % 30) * 6.9 } // spread ~700..900

        val lowVarSi = BaevskyStressIndex.compute(lowVariability)
        val highVarSi = BaevskyStressIndex.compute(highVariability)

        assertNotNull(lowVarSi)
        assertNotNull(highVarSi)
        assertTrue(
            "expected concentrated RR to score higher: $lowVarSi vs $highVarSi",
            lowVarSi!! > highVarSi!!
        )
    }

    @Test
    fun `detrend flattens a linear ramp`() {
        // A drifting heart rate must not inflate MxDMn; after detrending, a pure
        // ramp should collapse to near-constant around the series mean.
        val ramp = List(60) { 700.0 + it * 5.0 } // 700..995
        val detrended = BaevskyStressIndex.detrend(ramp)

        val rampRange = ramp.max() - ramp.min()
        val detrendedRange = detrended.max() - detrended.min()

        assertTrue(
            "detrended range ($detrendedRange) should be far below raw range ($rampRange)",
            detrendedRange < rampRange / 4
        )
        assertEquals(ramp.average(), detrended.average(), 1.0)
    }

    @Test
    fun `detrend preserves short series untouched`() {
        val short = List(BaevskyStressIndex.DETREND_WINDOW - 1) { 800.0 + it }
        assertEquals(short, BaevskyStressIndex.detrend(short))
    }

    @Test
    fun `a resting series lands on Baevsky's published scale`() {
        // Regression guard for the scale bug found on device: with the sqrt
        // transform applied, a resting trace scored ~7, which reads as broken
        // against Baevsky's reference ranges (50-150 low stress, >500 strong
        // sympathetic dominance) because those describe the RAW index.
        val rng = java.util.Random(42)
        val resting = List(120) { 850.0 + rng.nextGaussian() * 30.0 }

        val si = BaevskyStressIndex.compute(resting)

        assertNotNull(si)
        assertTrue("SI should be finite and positive, was $si", si!! > 0.0 && si.isFinite())
        assertTrue("SI should be on the raw scale, not sqrt-transformed — was $si", si > 10.0)
    }
}
