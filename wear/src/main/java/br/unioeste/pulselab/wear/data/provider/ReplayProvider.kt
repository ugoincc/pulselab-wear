package br.unioeste.pulselab.wear.data.provider

import android.content.Context
import br.unioeste.pulselab.wear.model.SensorBundle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [br.unioeste.pulselab.wear.data.provider.SensorProvider.providerId] for the
 * deterministic replay source (ESPECIFICACAO-protocolo-payload-v1.md §4).
 */
const val REPLAY_PROVIDER_ID = "replay"

/** Cadence of the real sensor (SENSORS.md §4.3: 1,00 Hz callbacks). */
const val REPLAY_DEFAULT_INTERVAL_MS = 1_000L

/**
 * The measurement instrument of Objetivo 5: a [SensorProvider] whose series is
 * known before it is transmitted, so loss, reordering and content divergence
 * are measurable by exact comparison against ground truth instead of by
 * inspecting whether the received value looks plausible
 * (cap6-avaliacao.tex §6.3.1, ESPECIFICACAO-protocolo-payload-v1.md §8).
 *
 * Three parameters are the experiment's factors:
 *
 * - [seed] fixes the series. Two runs with the same seed emit the same values,
 *   field by field, for every field that is not an instant of real time — see
 *   [ReplaySeries] for why timestamps are excluded and where they are recorded
 *   instead.
 * - [emitIntervalMs] is the cadence, defaulting to the real sensor's 1 Hz.
 *   cap6-avaliacao.tex §6.5 calls for multiples of it, to find the rate at
 *   which loss becomes significant.
 * - [batchSize] reproduces the delivery shape. `1` is the screen-on case; up to
 *   ~66 is the burst the platform hands over when the screen is dozing
 *   (SENSORS.md §4.1). The samples in a burst carry acquisition timestamps
 *   [emitIntervalMs] apart even though they are delivered together, which is
 *   the behaviour measured on device: the sensor samples continuously and
 *   stamps truthfully; only *delivery* is deferred.
 *
 * Every emitted sample is written to [EmissionLog] before the batch leaves —
 * that file is the set `S`. This is not optional bookkeeping: it is the half of
 * the instrument that makes `|S|` an observation rather than an assumption.
 *
 * @param logFactory invoked once per collection of [readingFlow], closed when
 *   collection ends. Null disables recording, which is only appropriate outside
 *   an evaluation run (e.g. a smoke test of the wiring).
 */
class ReplayProvider(
    private val seed: Long = DEFAULT_SEED,
    private val emitIntervalMs: Long = REPLAY_DEFAULT_INTERVAL_MS,
    private val batchSize: Int = 1,
    private val logFactory: (() -> EmissionLog)? = null,
    private val now: () -> Long = System::currentTimeMillis
) : SensorProvider {

    init {
        require(emitIntervalMs >= 0) { "emitIntervalMs must not be negative" }
        require(batchSize >= 1) { "batchSize must be at least 1" }
    }

    override val providerId: String = REPLAY_PROVIDER_ID

    /**
     * Everything true, including [CapabilitySet.providesEda], which no hardware
     * in the study supplies (ESPECIFICACAO §3: `edaTonic`/`edaScrPerMin` are
     * always null on the Galaxy Watch Ultra).
     *
     * That is the point rather than an oversight: cap6-avaliacao.tex §6.3.1
     * requires the replay to exercise the integrity of *every* field of the
     * contract, not only the ones a real sensor happens to fill. A field that
     * is never populated is a field whose serialisation across the bridge was
     * never tested.
     */
    override suspend fun capabilities(): CapabilitySet = CapabilitySet(
        providesHrv = true,
        providesBaevskySI = true,
        providesSpo2 = true,
        providesSkinTemp = true,
        providesEda = true,
        providesMotionFlag = true
    )

    /**
     * `sequenceNumber` restarts at 0 on every collection, matching
     * `SamsungSensorMonitor` and ESPECIFICACAO §5 — the collection *is* the
     * provider's connection lifecycle. The harness reconciles by
     * `(providerId, sequenceNumber)`, so a restart it does not know about would
     * read as a flood of duplicates; the run procedure (cap6-avaliacao.tex §6.6)
     * therefore records the restart instant, and the emission log's file name
     * scopes one file to one run.
     */
    override fun readingFlow(): Flow<List<SensorBundle>> = flow {
        val series = ReplaySeries(seed)
        val log = logFactory?.invoke()
        var sequenceNumber = 0L

        try {
            while (true) {
                val batch = ArrayList<SensorBundle>(batchSize)
                repeat(batchSize) {
                    delay(emitIntervalMs)
                    batch += series.next().toBundle(sequenceNumber++, now())
                }

                // Recorded before emitting, so a sample can never be received
                // without appearing in S. The reverse (in S, never received) is
                // exactly the loss the experiment measures; an unrecorded
                // arrival would instead be an instrument fault.
                val emittedAt = now()
                log?.let { sink ->
                    batch.forEach { sink.record(it, emittedAt) }
                    sink.flush()
                }

                emit(batch)
            }
        } finally {
            log?.close()
        }
    }

    /**
     * No connection to release — the flow's own `finally` closes the emission
     * log when collection ends. Present because callers manage providers
     * uniformly through [SensorProvider], and idempotent by being empty.
     */
    override fun disconnect() = Unit

    companion object {
        /**
         * Fixed by default so an operator who forgets to pass one still gets a
         * reproducible run. Changing it invalidates comparison with previously
         * recorded runs — it is a parameter of the experiment, not a constant.
         */
        const val DEFAULT_SEED = 20260803L

        /**
         * Largest burst measured on device with the screen dozing
         * (SENSORS.md §4.1, 2026-07-27).
         */
        const val SCREEN_OFF_BURST_SIZE = 66

        /**
         * Builds a provider that records to a run-scoped NDJSON file under the
         * app's external files dir — the configuration an actual evaluation run
         * uses.
         */
        fun forEvaluation(
            context: Context,
            seed: Long = DEFAULT_SEED,
            emitIntervalMs: Long = REPLAY_DEFAULT_INTERVAL_MS,
            batchSize: Int = 1
        ): ReplayProvider {
            val appContext = context.applicationContext
            return ReplayProvider(
                seed = seed,
                emitIntervalMs = emitIntervalMs,
                batchSize = batchSize,
                logFactory = { createRunEmissionLog(appContext, REPLAY_PROVIDER_ID) }
            )
        }
    }
}

/**
 * Projects a deterministic [ReplaySample] onto the wire payload, stamping the
 * instants that only the wall clock can supply.
 *
 * `spo2MeasuredAt`/`skinTempMeasuredAt` are derived by subtracting the sample's
 * deterministic channel age from [timestamp], so they honour the "null iff the
 * value is null" rule of ESPECIFICACAO §4 (never null here — every channel is
 * populated) while still reproducing the carry-forward age the real provider
 * exhibits.
 */
private fun ReplaySample.toBundle(sequenceNumber: Long, timestamp: Long) = SensorBundle(
    bpm = bpm,
    hrv = hrv,
    spo2 = spo2,
    skinTemp = skinTemp,
    baevskySI = baevskySI,
    edaTonic = edaTonic,
    edaScrPerMin = edaScrPerMin,
    motionFlag = motionFlag,
    timestamp = timestamp,
    sequenceNumber = sequenceNumber,
    spo2MeasuredAt = timestamp - spo2AgeMs,
    skinTempMeasuredAt = timestamp - skinTempAgeMs,
    providerId = REPLAY_PROVIDER_ID
)
