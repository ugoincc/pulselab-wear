package br.unioeste.pulselab.wear.data.health

import kotlin.math.sqrt

/**
 * Artefact gate built on the accelerometer. The accelerometer is NOT a stress
 * feature here — wrist movement corrupts PPG-derived IBI and smears EDA, so its
 * only job is to mark each emission as "the wearer was moving", letting the phone
 * exclude that window from the composite index.
 *
 * Deliberately a flag rather than a filter: the sample is still streamed and
 * stored, it just carries a motion marker. Discarding data outright would make the
 * dataset unauditable.
 *
 * Not thread-safe — drive it from a single callback thread.
 */
class MotionGate(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val threshold: Double = DEFAULT_THRESHOLD
) {

    private data class Sample(val atMs: Long, val magnitude: Double)

    private val samples = ArrayDeque<Sample>()

    /** Add one accelerometer sample; axes are in the SDK's raw integer units. */
    fun add(x: Int, y: Int, z: Int, atMs: Long) {
        val magnitude = sqrt((x.toDouble() * x) + (y.toDouble() * y) + (z.toDouble() * z))
        samples.addLast(Sample(atMs, magnitude))
        val cutoff = atMs - windowMs
        while (samples.isNotEmpty() && samples.first().atMs < cutoff) {
            samples.removeFirst()
        }
    }

    /**
     * Peak-to-peak magnitude over the window. This, not the magnitude itself, is
     * the motion statistic: at rest the vector magnitude sits at a constant ~1 g
     * whatever the axis orientation or unit scale, so its spread is near zero and
     * only real movement widens it.
     *
     * Exposed so the on-device calibration pass can read observed values straight
     * off Logcat and set [DEFAULT_THRESHOLD] from measured rest data.
     */
    fun peakToPeak(): Double? {
        if (samples.size < 2) return null
        return samples.maxOf { it.magnitude } - samples.minOf { it.magnitude }
    }

    /**
     * True when the window shows movement beyond [threshold]. Defaults to false
     * while the accelerometer is unsupported or has not delivered enough samples:
     * an absent accelerometer must not flag every sample as artefact-laden.
     */
    fun isMoving(): Boolean = (peakToPeak() ?: 0.0) > threshold

    fun clear() = samples.clear()

    companion object {
        /** One second — the cadence at which readings are emitted. */
        const val DEFAULT_WINDOW_MS = 1_000L

        /**
         * Peak-to-peak threshold in raw accelerometer units.
         *
         * First on-device measurement (SM-L705F, 2026-07-27, 150 windows) came out
         * cleanly bimodal: p50 = 0 with the watch resting still, ~400 while being
         * handled, and nothing in between. 200 sits in the empty middle.
         *
         * STILL PROVISIONAL, because that run had the watch lying on a desk, not
         * on a wrist: a worn watch has a nonzero floor from breathing and micro
         * movement. Re-measure with the watch actually worn, seated at a desk,
         * before trusting this for a collection session. [peakToPeak] is logged
         * every second so the calibration is a reading, not a guess.
         */
        const val DEFAULT_THRESHOLD = 200.0
    }
}
