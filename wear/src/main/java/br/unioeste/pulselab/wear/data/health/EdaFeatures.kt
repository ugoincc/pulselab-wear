package br.unioeste.pulselab.wear.data.health

/**
 * Lightweight electrodermal-activity features. EDA is the most specific channel
 * for pure sympathetic activation — unlike HRV, it carries no vagal confounder.
 *
 * Deliberately NOT a cvxEDA implementation: proper tonic/phasic decomposition
 * (Greco et al., 2016) is a convex optimisation problem, far too expensive to run
 * on the watch every second. These are cheap approximations good enough to drive a
 * live display; the citable decomposition is re-derived by the offline research
 * pipeline from the raw skin-conductance samples, which are streamed and stored
 * unmodified.
 *
 * Kept free of Samsung SDK types so it stays unit-testable, same as [Hrv] and
 * [BaevskyStressIndex].
 */

/**
 * Minimum phasic deflection, in microsiemens, for a local maximum to count as a
 * skin-conductance response. 0.02 uS is the usual amplitude floor in the EDA
 * literature; below it, peaks are indistinguishable from sensor noise.
 */
const val SCR_AMPLITUDE_THRESHOLD_US = 0.02

/**
 * Sliding time window of skin-conductance samples, mirroring [IbiWindow]: the
 * caller pushes samples as they arrive and reads derived features whenever it
 * emits.
 *
 * Not thread-safe — drive it from a single callback thread.
 */
class EdaWindow(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private data class Sample(val atMs: Long, val microSiemens: Double)

    private val samples = ArrayDeque<Sample>()

    /** Add one skin-conductance sample (uS) observed at [atMs], evicting stale ones. */
    fun add(microSiemens: Double, atMs: Long) {
        samples.addLast(Sample(atMs, microSiemens))
        val cutoff = atMs - windowMs
        while (samples.isNotEmpty() && samples.first().atMs < cutoff) {
            samples.removeFirst()
        }
    }

    /**
     * Tonic level (SCL) — the slow baseline of skin conductance, approximated as
     * the mean over the window. Null while the window is empty.
     */
    fun tonicLevel(): Double? =
        if (samples.isEmpty()) null else samples.sumOf { it.microSiemens } / samples.size

    /**
     * Phasic activity (SCR rate), in responses per minute: local maxima of the
     * signal rising more than [threshold] above the tonic level. Null until the
     * window spans enough time for a rate to mean anything.
     */
    fun scrPerMinute(threshold: Double = SCR_AMPLITUDE_THRESHOLD_US): Int? {
        if (samples.size < 3) return null
        val tonic = tonicLevel() ?: return null

        val values = samples.map { it.microSiemens }
        var peaks = 0
        for (i in 1 until values.lastIndex) {
            val phasic = values[i] - tonic
            if (phasic > threshold && values[i] > values[i - 1] && values[i] >= values[i + 1]) {
                peaks++
            }
        }

        val spanMinutes = (samples.last().atMs - samples.first().atMs) / 60_000.0
        if (spanMinutes <= 0.0) return null
        return (peaks / spanMinutes).toInt()
    }

    val size: Int get() = samples.size

    fun clear() = samples.clear()

    companion object {
        /**
         * 60 s matches [IbiWindow.DEFAULT_WINDOW_MS], so every feature in the
         * composite index is derived over the same span.
         */
        const val DEFAULT_WINDOW_MS = 60_000L
    }
}
