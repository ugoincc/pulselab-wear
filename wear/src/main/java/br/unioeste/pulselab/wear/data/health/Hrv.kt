package br.unioeste.pulselab.wear.data.health

import kotlin.math.sqrt

/**
 * HRV math for the project's primary biomarker. Kept free of any Samsung SDK
 * dependency so it is unit-testable and the algorithm is documented/justifiable
 * in the monograph (handoff §7) — we derive RMSSD ourselves instead of consuming
 * a black-box "stress score".
 */

/**
 * Status value (per IBI) that marks a sample as valid/reliable. Samsung Health
 * Sensor SDK convention is 0 == valid; anything else should be discarded so it
 * does not contaminate RMSSD (handoff §6.1). Confirm against on-device Logcat
 * during validation (checklist §10).
 */
const val IBI_STATUS_VALID = 0

/**
 * RMSSD (root mean square of successive differences) over a list of valid
 * inter-beat intervals in milliseconds. Returns null when there are fewer than
 * two intervals (RMSSD is undefined). See Jayasinghe & Torres (2023).
 */
fun rmssd(ibiMs: List<Double>): Double? {
    if (ibiMs.size < 2) return null
    var sumSq = 0.0
    for (i in 1 until ibiMs.size) {
        val diff = ibiMs[i] - ibiMs[i - 1]
        sumSq += diff * diff
    }
    return sqrt(sumSq / (ibiMs.size - 1))
}

/**
 * Accumulates valid IBI samples within a sliding time window and computes RMSSD
 * over them. The window length is a configurable parameter (handoff §7 calls out
 * 60 s vs 5 min as a design decision to document); defaults to 60 s.
 *
 * Not thread-safe — drive it from a single callback thread.
 */
class IbiWindow(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private data class Sample(val atMs: Long, val ibiMs: Double)

    private val samples = ArrayDeque<Sample>()

    /** Add one valid IBI (ms) observed at [atMs] (epoch ms), evicting stale ones. */
    fun add(ibiMs: Double, atMs: Long) {
        samples.addLast(Sample(atMs, ibiMs))
        val cutoff = atMs - windowMs
        while (samples.isNotEmpty() && samples.first().atMs < cutoff) {
            samples.removeFirst()
        }
    }

    /** RMSSD over the current window, or null if fewer than two intervals. */
    fun currentRmssd(): Double? = rmssd(samples.map { it.ibiMs })

    val size: Int get() = samples.size

    fun clear() = samples.clear()

    companion object {
        const val DEFAULT_WINDOW_MS = 60_000L
    }
}
