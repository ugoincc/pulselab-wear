package br.unioeste.pulselab.wear.data.health

import kotlin.math.floor

/**
 * Baevsky's Stress Index (SI) — the second HRV-domain feature of the composite
 * stress index. Computed from the very same filtered IBI list that already feeds
 * [rmssd], but far more responsive to sympathetic dominance than RMSSD alone.
 *
 * Like [Hrv], this file is deliberately free of any Samsung SDK dependency so the
 * algorithm stays unit-testable and citable in the monograph — the whole point of
 * building our own index instead of consuming Samsung's black-box stress score.
 *
 * SI = AMo / (2 x Mo x MxDMn)
 *
 * - Mo     "mode": the most frequent RR value, taken as the centre of the modal
 *          50 ms histogram bin, expressed in seconds.
 * - AMo    "mode amplitude": the share of RR intervals falling in that modal bin,
 *          as a percentage (0..100).
 * - MxDMn  "variation range": max RR - min RR, expressed in seconds.
 *
 * Reference: BAEVSKY, R. M. (2009). The detrending step follows the Kubios HRV
 * convention; the sqrt transform Kubios also applies is deliberately NOT applied
 * here — see [compute].
 */
object BaevskyStressIndex {

    /**
     * Histogram bin width in milliseconds. 50 ms is Baevsky's original convention
     * and the one Kubios implements.
     */
    const val BIN_WIDTH_MS = 50.0

    /**
     * Minimum number of RR intervals before SI is meaningful. Below this the
     * histogram has too few samples for AMo to be stable — at ~1 beat/s this is
     * roughly a 30 s window.
     */
    const val MIN_INTERVALS = 30

    /**
     * Length (in samples) of the centred moving average used to remove the slow
     * trend. Must be odd so the window is symmetric around each sample.
     */
    const val DETREND_WINDOW = 21

    /**
     * Baevsky SI over [rrIntervalsMs] (valid RR/IBI intervals, in milliseconds),
     * or null when the window is too short or degenerate.
     *
     * The slow trend is removed first ([detrend]), following Kubios, so a drifting
     * heart rate does not inflate MxDMn and deflate the index.
     *
     * Returned on the RAW scale, deliberately. Kubios also applies sqrt() to
     * correct the index's strong right skew, and an earlier version of this file
     * did the same — but Baevsky's published reference ranges (roughly 50-150 low
     * stress, >500 strong sympathetic dominance) describe the raw index, so
     * reporting a sqrt-transformed value against them is a category error. It
     * showed up immediately on device: a resting SI of ~44 was being displayed as
     * 6.6, which looks broken but was the transform, not the maths.
     *
     * The sqrt belongs in the offline statistical pipeline, where normality
     * actually matters. Here the interpretable scale wins.
     */
    fun compute(rrIntervalsMs: List<Double>): Double? {
        if (rrIntervalsMs.size < MIN_INTERVALS) return null

        val detrended = detrend(rrIntervalsMs)

        // Classic Baevsky binning: bin the RR values themselves in fixed 50 ms
        // bins. floor() (not toInt()) so the bin around zero is not double-width.
        val counts = HashMap<Int, Int>()
        for (rr in detrended) {
            val bin = floor(rr / BIN_WIDTH_MS).toInt()
            counts[bin] = (counts[bin] ?: 0) + 1
        }
        val modalBin = counts.maxByOrNull { it.value } ?: return null

        // Mo: centre of the modal bin, in seconds.
        val moSeconds = ((modalBin.key + 0.5) * BIN_WIDTH_MS) / 1000.0
        // AMo: share of intervals in the modal bin, as a percentage.
        val amo = (modalBin.value.toDouble() / detrended.size) * 100.0
        // MxDMn: variation range, in seconds.
        val mxDMnSeconds = (detrended.max() - detrended.min()) / 1000.0

        if (moSeconds <= 0.0 || mxDMnSeconds <= 0.0) return null

        return amo / (2.0 * moSeconds * mxDMnSeconds)
    }

    /**
     * Removes the slow (low-frequency) trend by subtracting a centred moving
     * average and adding the series mean back, so the result stays in the same
     * physiological range as real RR intervals — required, since [compute] bins
     * those values in absolute 50 ms bins.
     *
     * This is a deliberate simplification of the smoothness-priors method Kubios
     * uses: it costs one pass over a short window and is cheap enough to run on
     * the watch every second. The simplification is documented in the monograph;
     * the offline research pipeline re-derives SI with the full method.
     */
    fun detrend(series: List<Double>): List<Double> {
        if (series.size < DETREND_WINDOW) return series

        val mean = series.average()
        val half = DETREND_WINDOW / 2
        return series.mapIndexed { i, value ->
            // Clamp the window at the edges instead of shrinking it, so the first
            // and last samples are not collapsed onto the series mean.
            val from = (i - half).coerceAtLeast(0)
            val to = (i + half).coerceAtMost(series.lastIndex)
            var sum = 0.0
            for (j in from..to) sum += series[j]
            value - (sum / (to - from + 1)) + mean
        }
    }
}
