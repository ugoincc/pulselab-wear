package br.unioeste.pulselab.wear.data.health

import kotlin.math.abs
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
 * Reconstructs the end time of each interval in a batch that the SDK stamped with
 * a single timestamp.
 *
 * The Samsung SDK does not deliver one interval per data point: on device it
 * delivers long bursts (165 intervals in one point was observed) all carrying the
 * batch's timestamp. Treating them as simultaneous makes a sliding time window
 * meaningless — the whole batch enters and leaves together.
 *
 * The last interval ends when the batch was stamped; each earlier one ends one
 * interval sooner. Returns end times aligned index-for-index with [intervalsMs].
 */
fun ibiEndTimes(intervalsMs: List<Int>, batchEndMs: Long): LongArray {
    val endTimes = LongArray(intervalsMs.size)
    var backwards = 0L
    for (i in intervalsMs.indices.reversed()) {
        endTimes[i] = batchEndMs - backwards
        backwards += intervalsMs[i].toLong()
    }
    return endTimes
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

    /** Last interval that passed the filters — the reference for the Malik rule. */
    private var lastAccepted: Double? = null
    private var consecutiveRejections = 0

    /**
     * Filter counters since the session began, for on-device diagnosis.
     *
     * Both are cumulative, unlike [size], which is a 60 s sliding window. Reading
     * [rejected] against [size] is the mistake to avoid — "40 IBIs (38 rejected)"
     * looks like a 49 % rejection rate and is nothing of the sort; it is 38
     * rejections since launch against whatever happens to be in the last minute.
     * Use [rejectionRate], which compares like with like.
     */
    var rejected: Int = 0
        private set

    var accepted: Int = 0
        private set

    /** Share of offered intervals the filters threw away, or null before any. */
    val rejectionRate: Double?
        get() = (accepted + rejected).takeIf { it > 0 }?.let { rejected.toDouble() / it }

    /**
     * Offers one IBI (ms) observed at [atMs] (epoch ms) to the window, returning
     * whether it was accepted. Stale samples are evicted on every call.
     *
     * The SDK's own status flag is not enough: on-device validation (SM-L705F,
     * 2026-07-27) showed intervals ~700 ms out of line slipping through and
     * driving RMSSD to 300 ms at a resting heart rate of 100 bpm — a missed beat
     * reported as one long interval. RMSSD squares successive differences, so a
     * single artefact like that dominates the whole window.
     *
     * Two standard HRV preprocessing filters, both citable:
     *  1. Absolute plausibility: outside [MIN_PLAUSIBLE_IBI_MS]..[MAX_PLAUSIBLE_IBI_MS]
     *     no interval is physiological, whatever the sensor claims.
     *  2. Malik rule (Task Force, 1996): reject an interval differing more than
     *     [MALIK_MAX_RELATIVE_CHANGE] from the previous accepted one.
     *
     * The Malik rule alone would deadlock if the reference itself were wrong or
     * the heart rate genuinely jumped (standing up, re-seating the watch), so
     * after [MAX_CONSECUTIVE_REJECTIONS] refusals in a row the window accepts the
     * value and re-anchors on it.
     */
    fun add(ibiMs: Double, atMs: Long): Boolean {
        if (ibiMs < MIN_PLAUSIBLE_IBI_MS || ibiMs > MAX_PLAUSIBLE_IBI_MS) {
            rejected++
            return false
        }

        val previous = lastAccepted
        if (previous != null &&
            abs(ibiMs - previous) > MALIK_MAX_RELATIVE_CHANGE * previous &&
            consecutiveRejections < MAX_CONSECUTIVE_REJECTIONS
        ) {
            consecutiveRejections++
            rejected++
            return false
        }

        consecutiveRejections = 0
        lastAccepted = ibiMs
        accepted++
        samples.addLast(Sample(atMs, ibiMs))
        val cutoff = atMs - windowMs
        while (samples.isNotEmpty() && samples.first().atMs < cutoff) {
            samples.removeFirst()
        }
        return true
    }

    /** RMSSD over the current window, or null if fewer than two intervals. */
    fun currentRmssd(): Double? = rmssd(currentIntervals())

    /**
     * The valid RR intervals currently in the window, oldest first. Exposed so
     * [BaevskyStressIndex] derives SI from exactly the same filtered intervals
     * that produced RMSSD — two features over one window, never two windows.
     */
    fun currentIntervals(): List<Double> = samples.map { it.ibiMs }

    /** Baevsky SI over the current window, or null if the window is too short. */
    fun currentStressIndex(): Double? = BaevskyStressIndex.compute(currentIntervals())

    val size: Int get() = samples.size

    fun clear() {
        samples.clear()
        lastAccepted = null
        consecutiveRejections = 0
        rejected = 0
        accepted = 0
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 60_000L

        /** 300 ms = 200 bpm. Nothing shorter is a real beat-to-beat interval. */
        const val MIN_PLAUSIBLE_IBI_MS = 300.0

        /** 2000 ms = 30 bpm. Longer almost always means a missed beat. */
        const val MAX_PLAUSIBLE_IBI_MS = 2000.0

        /** Malik rule threshold: 20 % change from the previous accepted interval. */
        const val MALIK_MAX_RELATIVE_CHANGE = 0.20

        /** Refusals in a row before the window re-anchors on the new value. */
        const val MAX_CONSECUTIVE_REJECTIONS = 5
    }
}
