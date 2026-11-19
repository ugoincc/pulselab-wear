package br.unioeste.pulselab.wear.data.provider

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * One sample of the deterministic replay series — every payload field except
 * the ones that can only come from the wall clock.
 *
 * The split is deliberate and is what makes "same seed ⇒ same series, field by
 * field" (cap6-avaliacao.tex §6.3.1, requisito de determinismo) a testable
 * claim: `timestamp`, `spo2MeasuredAt` and `skinTempMeasuredAt` are instants of
 * real time, so they cannot be reproduced across runs and are not part of the
 * deterministic series. They are stamped by [ReplayProvider] at emission and
 * recorded in the NDJSON emission log, which is what the analysis harness
 * compares against — not a re-derived expectation.
 *
 * [spo2AgeMs] and [skinTempAgeMs] *are* deterministic: they are how far in the
 * past that channel's measurement completed, relative to this sample's
 * acquisition instant. They exist so the replay exercises the carry-forward
 * semantics of the real provider (ESPECIFICACAO-protocolo-payload-v1.md §3,
 * `spo2`/`skinTemp`), where a value can be minutes old by the time it rides
 * along on an HR emission.
 */
data class ReplaySample(
    val bpm: Int,
    val hrv: Int,
    val spo2: Int,
    val skinTemp: Double,
    val baevskySI: Double,
    val edaTonic: Double,
    val edaScrPerMin: Int,
    val motionFlag: Boolean,
    val spo2AgeMs: Long,
    val skinTempAgeMs: Long
)

/**
 * The ground truth of the evaluation (Objetivo 5): a series generated from a
 * fixed seed, so the evaluator knows every value before it is transmitted and
 * content divergence is measurable by exact comparison rather than by
 * plausibility inspection (ESPECIFICACAO-protocolo-payload-v1.md §8).
 *
 * This is emphatically **not** the old `SIMULATED` source (removed in
 * `d7f6a593`): that one drew fresh random noise each run, so there was no
 * ground truth to compare a received value against. Here the seed is the
 * ground truth.
 *
 * The values are a bounded random walk rather than independent draws, so the
 * series has the shape of physiology (successive samples correlated, occasional
 * motion) without pretending to be physiology. Nothing downstream may draw a
 * conclusion about the sensor from it — cap6-avaliacao.tex §6.5 flags exactly
 * this as a construct-validity threat: what is measured here is the transport,
 * not the acquisition.
 *
 * Determinism rests on the draws being consumed in a fixed order: [next] pulls
 * from [Random] in the same sequence every call, and the generator is
 * stateful/sequential rather than index-addressable. Do not reorder or add
 * draws inside [next] without accepting that every previously recorded series
 * for that seed changes.
 */
class ReplaySeries(private val seed: Long) {

    private val random = Random(seed)

    private var bpm = 70.0
    private var hrv = 45.0
    private var spo2 = 97.0
    private var skinTemp = 34.5
    private var baevskySI = 90.0
    private var edaTonic = 4.0

    /** Next sample in the series. Stateful: call order is the series order. */
    fun next(): ReplaySample {
        bpm = walk(bpm, step = 1.5, min = 45.0, max = 180.0)
        hrv = walk(hrv, step = 3.0, min = 5.0, max = 200.0)
        spo2 = walk(spo2, step = 0.4, min = 90.0, max = 100.0)
        skinTemp = walk(skinTemp, step = 0.05, min = 31.0, max = 38.0)
        // Raw Baevsky scale, no sqrt — the reference bands (~50–150 at rest)
        // describe the raw index (ESPECIFICACAO §3, BaevskyStressIndex.compute).
        baevskySI = walk(baevskySI, step = 6.0, min = 20.0, max = 900.0)
        edaTonic = walk(edaTonic, step = 0.3, min = 0.5, max = 25.0)

        return ReplaySample(
            bpm = bpm.toInt(),
            hrv = hrv.roundToInt(),
            spo2 = spo2.roundToInt(),
            skinTemp = round(skinTemp, 2),
            baevskySI = round(baevskySI, 1),
            edaTonic = round(edaTonic, 3),
            edaScrPerMin = random.nextInt(0, 13),
            // ~15 % of samples flagged as movement, so the phone side sees both
            // states without the gate dominating the series.
            motionFlag = random.nextInt(100) < 15,
            // SpO2 is re-armed every 5 min on the real provider, so its carried
            // value can be that old; skin temperature is continuous and fresh.
            spo2AgeMs = random.nextLong(0, 5 * 60_000L),
            skinTempAgeMs = random.nextLong(0, 2_000L)
        )
    }

    private fun walk(current: Double, step: Double, min: Double, max: Double): Double {
        val next = current + (random.nextDouble() - 0.5) * 2.0 * step
        return next.coerceIn(min, max)
    }

    /**
     * Rounded to a fixed number of decimals so the emitted value survives JSON
     * serialisation and the phone's `getDouble` unchanged. Comparing a full
     * binary64 random draw field-by-field across two languages would turn the
     * content-divergence metric (cap6-avaliacao.tex §6.2) into a test of
     * float formatting.
     */
    private fun round(value: Double, decimals: Int): Double {
        var factor = 1.0
        repeat(decimals) { factor *= 10 }
        return (value * factor).roundToInt() / factor
    }

    override fun toString(): String = "ReplaySeries(seed=$seed)"
}
