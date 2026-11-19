# Handoff: conteúdo completo restante — resposta

Todos os 9 arquivos pedidos foram localizados nos caminhos supostos, exceto um pequeno ajuste de nome/local (item 6, já indicado como "confirme o caminho exato" pelo próprio handoff). Nenhum arquivo estava ausente.

---

## 1. Arquivos — conteúdo completo

### `wear/src/main/java/br/unioeste/pulselab/wear/data/health/SamsungSensorMonitor.kt`
```kotlin
package br.unioeste.pulselab.wear.data.health

import android.content.Context
import android.util.Log
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import br.unioeste.pulselab.wear.model.SensorReading
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val TAG = "SamsungSensorMonitor"

// Data-point status integers. These are runtime values from the sensor, not part
// of the .aar bytecode. Confirmed against on-device Logcat (SM-L705F, 2026-06-28):
// each metric uses a DIFFERENT "valid" code, which is why on-device validation
// was required.
private const val HR_STATUS_VALID = 1        // confirmed: reliable HR + IBI
private const val HR_STATUS_OFF_BODY = -3    // watch off the wrist
private const val SPO2_STATUS_COMPLETED = 2  // confirmed: on-demand measurement finished
private const val SKIN_TEMP_STATUS_VALID = 0 // confirmed: 0 == valid (NOT 1)

/**
 * How often the on-demand SpO2 tracker is re-armed.
 *
 * Each arm is one ~30 s measurement with the optical LEDs lit, so re-arming every
 * minute kept the sensor burning roughly half the time for a number that barely
 * moves at rest (SpO2 changed between consecutive samples in 8 % of them, and
 * mostly by one point). Five minutes is still far denser than the physiology
 * warrants and costs about a tenth of the duty cycle.
 */
private const val SPO2_REARM_INTERVAL_MS = 5 * 60_000L

/**
 * The watch's single source of biometrics, backed by the Samsung Health Sensor
 * SDK. Emits one batch of [SensorReading] per SDK callback, carrying every
 * channel the watch can read.
 *
 * Batches, not single readings, because that is the shape the platform actually
 * delivers: with the screen on a batch is usually one sample, but while dozing
 * delivery is withheld and arrives in bursts of up to 66 (SENSORS.md §4.1).
 * Preserving that boundary lets the phone receive one Data Layer message per
 * burst instead of one per sample.
 *
 * Two of the six values are computed here rather than read: RMSSD ([IbiWindow])
 * and the Baevsky Stress Index ([BaevskyStressIndex]), both over the same window
 * of filtered IBI intervals. The accelerometer contributes no value of its own —
 * it only sets [SensorReading.motionFlag] through [MotionGate], so the phone can
 * exclude movement-contaminated windows from the composite index.
 *
 * Cadence model: HEART_RATE_CONTINUOUS (~1 Hz) is the driver. The slower or
 * higher-rate channels (SpO2, skin temperature, accelerometer) update shared
 * "latest" state that rides along on the next HR emission.
 *
 * EDA is not among them: EDA_CONTINUOUS is absent from this hardware's capability
 * list, so the tracker was never anything but a warning in the log. The feature
 * maths stays in [EdaWindow], dormant, for a watch that supports it.
 */
class SamsungSensorMonitor(context: Context) {

    private val appContext = context.applicationContext

    /**
     * @param windowMs RMSSD/SI sliding-window length.
     */
    fun readingFlow(
        windowMs: Long = IbiWindow.DEFAULT_WINDOW_MS,
        spo2RearmMs: Long = SPO2_REARM_INTERVAL_MS
    ): Flow<List<SensorReading>> =
        callbackFlow {
            val connection = SensorConnectionManager(appContext)
            val ibiWindow = IbiWindow(windowMs)
            val motionGate = MotionGate()

            // Secondary metrics updated by their own trackers; attached to the
            // next HR emission. Single-threaded callbacks, so plain vars are fine.
            var latestSpo2: Int? = null
            var latestSkinTemp: Double? = null

            var hrTracker: HealthTracker? = null
            var spo2Tracker: HealthTracker? = null
            var skinTempTracker: HealthTracker? = null
            var accelTracker: HealthTracker? = null
            var spo2Job: Job? = null

            val hrListener = object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: List<DataPoint>) {
                    // One emission per callback, not per data point: this callback IS
                    // the platform's burst boundary (up to 66 samples at once with the
                    // screen dozing), and the phone should receive one Data Layer
                    // message per burst rather than one per sample.
                    val batch = ArrayList<SensorReading>(dataPoints.size)

                    for (dp in dataPoints) {
                        val hr = dp.getValue(ValueKey.HeartRateSet.HEART_RATE)
                        val hrStatus = dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)
                        val ibiList = dp.getValue(ValueKey.HeartRateSet.IBI_LIST) ?: emptyList()
                        val ibiStatus = dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) ?: emptyList()

                        // Raw IBI + status logged so the SDK's status semantics can be
                        // confirmed on device: IBI_STATUS_VALID is still an assumption,
                        // and it demonstrably lets artefacts through (see IbiWindow.add).
                        //
                        // lagMs = how far behind the wall clock the sample's own
                        // timestamp is. With the screen on this sits near zero; the
                        // question it exists to answer is what happens across a
                        // screen-off window, where the SDK withholds delivery and
                        // flushes a burst on wake. A lag that grows with the burst
                        // means timestamps are real sample times (series intact); a
                        // lag near zero for every sample in the burst would mean they
                        // are delivery times, and the time series would be worthless.
                        val lagMs = System.currentTimeMillis() - dp.getTimestamp()
                        Log.d(
                            TAG,
                            "HR=$hr status=$hrStatus lagMs=$lagMs ibi=$ibiList ibiStatus=$ibiStatus"
                        )

                        if (hrStatus == HR_STATUS_OFF_BODY) continue  // off-wrist → ignore

                        // IBI does NOT arrive at 1 Hz: on device (SM-L705F) 172 of 180
                        // data points carried an empty list and a single one carried
                        // 165 intervals at once. The SDK stamps that whole batch with
                        // one timestamp, so stamping every interval with it would make
                        // the batch enter and leave the sliding window as one block —
                        // observed as the window collapsing from 115 samples to 4.
                        //
                        val endTimes = ibiEndTimes(ibiList, dp.getTimestamp())

                        // Added oldest first, so the window's successive-difference
                        // filter compares intervals in the order the heart produced them.
                        // Keep only valid IBIs, else RMSSD and SI both explode. The
                        // window applies the physiological filters on top of this flag.
                        ibiList.forEachIndexed { i, ibi ->
                            if (ibiStatus.getOrNull(i) == IBI_STATUS_VALID && ibi > 0) {
                                ibiWindow.add(ibi.toDouble(), endTimes[i])
                            } else {
                                // Tell the window a beat is missing here, so it does
                                // not pair the intervals either side of the hole as
                                // if they had followed one another.
                                ibiWindow.noteDropped()
                            }
                        }

                        if (hr != null && hr > 0 && hrStatus == HR_STATUS_VALID) {
                            val rmssd = ibiWindow.currentRmssd()
                            val si = ibiWindow.currentStressIndex()
                            val moving = motionGate.isMoving()

                            Log.d(
                                TAG,
                                "HR=$hr RMSSD=${rmssd?.let { "%.1f".format(it) } ?: "—"} " +
                                    "SI=${si?.let { "%.1f".format(it) } ?: "—"} over ${ibiWindow.size} IBIs " +
                                    "(descarte ${ibiWindow.rejectionRate?.let { "%.0f%%".format(it * 100) } ?: "—"} " +
                                    "acumulado: ${ibiWindow.rejected}/${ibiWindow.accepted + ibiWindow.rejected}), " +
                                    "motion=$moving p2p=${motionGate.peakToPeak()?.let { "%.0f".format(it) } ?: "—"}"
                            )

                            batch += SensorReading(
                                bpm = hr.toDouble(),
                                hrv = rmssd?.roundToInt(),
                                spo2 = latestSpo2,
                                skinTemp = latestSkinTemp,
                                baevskySI = si,
                                // Always null on this hardware — kept in the payload
                                // so the phone contract does not change if a watch
                                // with EDA shows up. See [EdaWindow].
                                edaTonic = null,
                                edaScrPerMin = null,
                                motionFlag = moving,
                                timestamp = dp.getTimestamp()
                            )
                        }
                    }

                    if (batch.isNotEmpty()) trySend(batch)
                }

                override fun onFlushCompleted() { Log.d(TAG, "HR flush completed") }
                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "HR tracker error: $error")
                }
            }

            val spo2Listener = object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: List<DataPoint>) {
                    for (dp in dataPoints) {
                        val status = dp.getValue(ValueKey.SpO2Set.STATUS)
                        val spo2 = dp.getValue(ValueKey.SpO2Set.SPO2)
                        if (status == SPO2_STATUS_COMPLETED && spo2 != null && spo2 > 0) {
                            latestSpo2 = spo2
                        }
                    }
                }

                override fun onFlushCompleted() {}
                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "SpO2 tracker error: $error")
                }
            }

            val skinTempListener = object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: List<DataPoint>) {
                    for (dp in dataPoints) {
                        val status = dp.getValue(ValueKey.SkinTemperatureSet.STATUS)
                        val temp = dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
                        if (status == SKIN_TEMP_STATUS_VALID && temp != null && temp > 0) {
                            latestSkinTemp = temp.toDouble()
                        }
                    }
                }

                override fun onFlushCompleted() {}
                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "SkinTemp tracker error: $error")
                }
            }

            val accelListener = object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: List<DataPoint>) {
                    for (dp in dataPoints) {
                        val x = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X)
                        val y = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y)
                        val z = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z)
                        if (x != null && y != null && z != null) {
                            motionGate.add(x, y, z, dp.getTimestamp())
                        }
                    }
                }

                override fun onFlushCompleted() {}
                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "Accelerometer tracker error: $error")
                }
            }

            connection.onConnected = {
                val supported = connection.supportedTypes()

                // Capability check is the definitive gate: only start trackers this
                // watch actually reports. getHealthTracker can still reject a type
                // the capability list advertises (BIA does exactly that on the
                // Galaxy Watch Ultra), so every instantiation is also guarded.
                fun startContinuous(
                    type: HealthTrackerType,
                    listener: HealthTracker.TrackerEventListener
                ): HealthTracker? {
                    if (type !in supported) {
                        Log.w(TAG, "$type not supported on this device")
                        return null
                    }
                    return runCatching {
                        connection.tracker(type)?.also { it.setEventListener(listener) }
                    }.onFailure {
                        Log.e(TAG, "Could not start $type", it)
                    }.getOrNull()
                }

                hrTracker = startContinuous(HealthTrackerType.HEART_RATE_CONTINUOUS, hrListener)
                skinTempTracker = startContinuous(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS, skinTempListener)
                accelTracker = startContinuous(HealthTrackerType.ACCELEROMETER_CONTINUOUS, accelListener)

                // SpO2 is on-demand: each arm yields one ~30 s measurement, so
                // re-arm on a loop to keep it fresh. Only one on-demand tracker may
                // run at a time. The loop is a child of the flow scope, so it is
                // cancelled automatically when collection stops.
                if (HealthTrackerType.SPO2_ON_DEMAND in supported) {
                    spo2Job = this@callbackFlow.launch {
                        while (isActive) {
                            spo2Tracker?.unsetEventListener()
                            spo2Tracker = runCatching {
                                connection.tracker(HealthTrackerType.SPO2_ON_DEMAND)
                                    ?.also { it.setEventListener(spo2Listener) }
                            }.getOrNull()
                            delay(spo2RearmMs)
                        }
                    }
                } else {
                    Log.w(TAG, "SPO2_ON_DEMAND not supported on this device")
                }
            }

            connection.onError = { e ->
                Log.e(TAG, "Sensor connection error code=${e.errorCode}")
                close(e)
            }

            connection.connect()

            awaitClose {
                spo2Job?.cancel()
                hrTracker?.unsetEventListener()
                spo2Tracker?.unsetEventListener()
                skinTempTracker?.unsetEventListener()
                accelTracker?.unsetEventListener()
                connection.disconnect()
                Log.d(TAG, "Monitor closed — trackers unset, service disconnected")
            }
        }
}
```

### `wear/src/main/java/br/unioeste/pulselab/wear/data/health/Hrv.kt`
```kotlin
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
 * How long the window may go without an accepted interval before the beats on
 * either side stop counting as consecutive.
 *
 * Deliberately coarse. An earlier attempt tested succession exactly, asking
 * whether `atMs[i] - atMs[i-1]` matched `ibiMs[i]`, and it collapsed on real
 * data: the SDK delivers one interval per data point 95 % of the time (247 of 261
 * measured), and those data points are stamped 1000 ms apart while the interval
 * between the beats is ~750 ms. [ibiEndTimes] anchors each batch on the data
 * point's timestamp, which is when it was *delivered*, not when the last beat
 * ended, so the arithmetic never chains across batches. Every beat became its own
 * run, almost no pair survived, and RMSSD froze at one value.
 *
 * Beat-level breaks are reported directly by whoever drops a beat — see
 * [IbiWindow.noteDropped] and the filters inside [IbiWindow.add]. This threshold
 * only has to catch what nobody reports: the sensor going quiet, which on device
 * lasts ~11 s (SENSORS.md §4.3). Normal delivery never exceeds ~2 s.
 */
const val RUN_BREAK_GAP_MS = 3_000L

/**
 * RMSSD (root mean square of successive differences) over a list of valid
 * inter-beat intervals in milliseconds. Returns null when there are fewer than
 * two intervals (RMSSD is undefined). See Jayasinghe & Torres (2023).
 *
 * Treats the list as one uninterrupted run of beats. When the series may have
 * holes — rejected artefacts, or the sensor's periodic ~11 s dropout (SENSORS.md
 * §4.3) — use [rmssdOverRuns] instead, or the difference across the hole enters
 * the sum as if it were a successive pair.
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
 * RMSSD pooled over runs of genuinely consecutive beats.
 *
 * RMSSD is defined on *successive* differences, so every pair it sums has to be
 * two beats that actually followed one another. A gap in the series — whether a
 * filtered-out artefact or the sensor's periodic dropout — makes the beats on
 * either side adjacent in the list without being adjacent in the heart, and
 * pairing them fabricates a difference that was never observed.
 *
 * The standard treatment is to break the series at each discontinuity and pool
 * the successive differences of the surviving runs (Task Force, 1996, §on editing
 * artefacts): one difference per genuinely consecutive pair, none across a hole.
 * Pooling rather than averaging per-run values keeps every pair weighted equally,
 * so a two-beat run does not count as much as a fifty-beat one.
 *
 * Returns null when no run contributes a pair.
 */
fun rmssdOverRuns(runs: List<List<Double>>): Double? {
    var sumSq = 0.0
    var pairs = 0
    for (run in runs) {
        for (i in 1 until run.size) {
            val diff = run[i] - run[i - 1]
            sumSq += diff * diff
            pairs++
        }
    }
    return if (pairs == 0) null else sqrt(sumSq / pairs)
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

    /** [startsRun] marks a beat that did not follow the one before it. */
    private data class Sample(val atMs: Long, val ibiMs: Double, val startsRun: Boolean)

    private val samples = ArrayDeque<Sample>()

    /** Last interval that passed the filters — the reference for the Malik rule. */
    private var lastAccepted: Double? = null
    private var lastAcceptedAtMs: Long? = null
    private var consecutiveRejections = 0

    /** A beat was dropped since the last accepted one, so the chain is broken. */
    private var chainBroken = true

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
            noteDropped()
            return false
        }

        val previous = lastAccepted
        if (previous != null &&
            abs(ibiMs - previous) > MALIK_MAX_RELATIVE_CHANGE * previous &&
            consecutiveRejections < MAX_CONSECUTIVE_REJECTIONS
        ) {
            consecutiveRejections++
            rejected++
            noteDropped()
            return false
        }

        // A beat follows the previous one unless something was dropped in between
        // or the sensor went quiet for long enough that beats must have been lost.
        val quietTooLong = lastAcceptedAtMs?.let { atMs - it > RUN_BREAK_GAP_MS } ?: true
        val startsRun = chainBroken || quietTooLong

        consecutiveRejections = 0
        chainBroken = false
        lastAccepted = ibiMs
        lastAcceptedAtMs = atMs
        accepted++
        samples.addLast(Sample(atMs, ibiMs, startsRun))
        val cutoff = atMs - windowMs
        while (samples.isNotEmpty() && samples.first().atMs < cutoff) {
            samples.removeFirst()
        }
        return true
    }

    /**
     * Reports a beat that never reached [add] — an interval the SDK itself flagged
     * invalid.
     *
     * Those are dropped before the window ever sees them, so without this the
     * window would pair the beats either side of the hole as if they had followed
     * one another.
     */
    fun noteDropped() {
        chainBroken = true
    }

    /**
     * RMSSD over the current window, computed only across genuinely consecutive
     * beats. Null when no pair in the window qualifies.
     */
    fun currentRmssd(): Double? = rmssdOverRuns(currentRuns())

    /**
     * The window split into runs of consecutive beats, oldest first.
     *
     * A run breaks wherever a beat is known to be missing: an interval the filters
     * rejected, one the SDK flagged invalid ([noteDropped]), or a silence longer
     * than [RUN_BREAK_GAP_MS]. On device the last case is the common one — the
     * heart-rate tracker goes quiet for ~11 s about once a minute (SENSORS.md
     * §4.3), which puts one hole in almost every 60 s window.
     */
    fun currentRuns(): List<List<Double>> {
        val runs = mutableListOf<List<Double>>()
        var run = mutableListOf<Double>()

        for (sample in samples) {
            if (sample.startsRun && run.isNotEmpty()) {
                runs += run
                run = mutableListOf()
            }
            run += sample.ibiMs
        }
        if (run.isNotEmpty()) runs += run
        return runs
    }

    /**
     * The valid RR intervals currently in the window, oldest first. Exposed so
     * [BaevskyStressIndex] derives SI from exactly the same filtered intervals
     * that produced RMSSD — two features over one window, never two windows.
     *
     * Flat and gap-blind on purpose: SI comes from a histogram of interval
     * lengths, which does not care what order the beats arrived in, so the runs
     * that matter to RMSSD are irrelevant here.
     */
    fun currentIntervals(): List<Double> = samples.map { it.ibiMs }

    /** Baevsky SI over the current window, or null if the window is too short. */
    fun currentStressIndex(): Double? = BaevskyStressIndex.compute(currentIntervals())

    val size: Int get() = samples.size

    fun clear() {
        samples.clear()
        lastAccepted = null
        lastAcceptedAtMs = null
        chainBroken = true
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
```

### `wear/src/main/java/br/unioeste/pulselab/wear/data/health/SensorConnectionManager.kt`
```kotlin
package br.unioeste.pulselab.wear.data.health

import android.content.Context
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.HealthTrackerType

private const val TAG = "SensorConnectionMgr"

/**
 * Owns the connection to the Samsung Health Platform (handoff §4). Reads sensors
 * directly on the watch — does NOT go through Samsung Health or Health Connect.
 *
 * Requires Health Platform [Dev mode] on the device; without it the SDK reports
 * SDK_POLICY_ERROR (handoff §2 / §11).
 */
class SensorConnectionManager(context: Context) {

    private val appContext = context.applicationContext
    private var service: HealthTrackingService? = null

    /** Invoked once the platform is connected and capability can be queried. */
    var onConnected: (() -> Unit)? = null

    /** Invoked on connection failure with the raw exception. */
    var onError: ((HealthTrackerException) -> Unit)? = null

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            Log.d(TAG, "Health Platform connected — supported trackers: ${supportedTypes()}")
            onConnected?.invoke()
        }

        override fun onConnectionEnded() {
            Log.d(TAG, "Health Platform connection ended")
        }

        override fun onConnectionFailed(e: HealthTrackerException) {
            Log.e(TAG, "Connection failed code=${e.errorCode} hasResolution=${e.hasResolution()}", e)
            // e.resolve(activity) needs an Activity (e.g. to install/update the
            // Health Platform). We collect in the background from the ViewModel,
            // so surface the error and let the caller drive resolution if needed.
            onError?.invoke(e)
        }
    }

    fun connect() {
        service = HealthTrackingService(connectionListener, appContext)
        service?.connectService()
    }

    fun disconnect() {
        service?.disconnectService()
        service = null
    }

    /**
     * The definitive list of trackers available on THIS watch — the gate the
     * handoff (§5) insists on before instantiating any tracker. Empty until
     * [onConnected] has fired.
     */
    fun supportedTypes(): List<HealthTrackerType> =
        service?.trackingCapability?.supportHealthTrackerTypes ?: emptyList()

    fun isSupported(type: HealthTrackerType): Boolean = type in supportedTypes()

    /** Returns a tracker for [type], or null if not connected. */
    fun tracker(type: HealthTrackerType): HealthTracker? = service?.getHealthTracker(type)
}
```

### `wear/src/main/java/br/unioeste/pulselab/wear/data/health/MotionGate.kt`
```kotlin
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
```

### `wear/src/main/java/br/unioeste/pulselab/wear/data/health/EdaFeatures.kt`
(caminho confirmado pelo import implícito no teste — `EdaFeaturesTest.kt` está no mesmo pacote `br.unioeste.pulselab.wear.data.health` e não tem `import` explícito para a classe sob teste, por estarem no mesmo pacote; localizado por nome de arquivo.)
```kotlin
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
 * DORMANT on current hardware. `EDA_CONTINUOUS` is absent from the Galaxy Watch
 * Ultra's capability list (confirmed on device, 2026-07-27), so nothing ever
 * calls [add] and every reader gets null. Kept wired end-to-end — tracker,
 * window, wire fields, phone-side feature — because what is missing is the
 * sensor, not the code: on a watch that exposes the tracker this starts
 * producing values with no change here.
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
```

### `wear/src/main/java/br/unioeste/pulselab/wear/data/wearable/WearDataSender.kt`
(caminho corrigido: o handoff supôs `data/health/WearDataSender.kt`; o arquivo real está em `data/wearable/`, não em `data/health/`.)
```kotlin
package br.unioeste.pulselab.wear.data.wearable

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import br.unioeste.pulselab.wear.model.MessagePaths
import br.unioeste.pulselab.wear.model.SensorBatch
import kotlinx.coroutines.tasks.await

class WearDataSender {
    private val gson = Gson()

    /** Sends one burst as a single Data Layer message. Returns false if no node took it. */
    suspend fun send(context: Context, batch: SensorBatch): Boolean {
        if (batch.samples.isEmpty()) return true
        return try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            if (nodes.isEmpty()) {
                Log.w("WearDataSender", "No connected Data Layer nodes — is the phone paired and reachable?")
                return false
            }
            val payload = gson.toJson(batch).toByteArray()
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, MessagePaths.SENSOR_BATCH, payload)
                    .await()
            }
            Log.d("WearDataSender", "Sent batch of ${batch.samples.size} samples (${payload.size} B)")
            true
        } catch (e: Exception) {
            Log.e("WearDataSender", "Failed to send sensor batch", e)
            false
        }
    }
}
```

### `wear/src/main/java/br/unioeste/pulselab/wear/data/health/SensorStreamService.kt`
(caminho confirmado — é exatamente o suposto, sob `data/health/`, não em outro subpacote.)
```kotlin
package br.unioeste.pulselab.wear.data.health

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import br.unioeste.pulselab.wear.R
import br.unioeste.pulselab.wear.data.wearable.WearDataSender
import br.unioeste.pulselab.wear.model.SensorBatch
import br.unioeste.pulselab.wear.model.SensorBundle
import br.unioeste.pulselab.wear.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "SensorStreamService"
private const val CHANNEL_ID = "sensor_stream"
private const val NOTIFICATION_ID = 2001

/**
 * Foreground service that owns the Samsung Health Sensor SDK session so
 * monitoring survives the screen turning off and the Activity being destroyed.
 * The trackers used by [SamsungSensorMonitor] stop delivering when their
 * process loses foreground standing — running them inside a
 * foregroundServiceType="health" service keeps the stream alive.
 *
 * Data path: SamsungSensorMonitor → [SensorStreamStore] (UI mirror) →
 * [WearDataSender] (phone). Same bundle, same transport as every other source.
 */
class SensorStreamService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sender = WearDataSender()
    private var streamJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (streamJob?.isActive == true) return START_STICKY

        startAsForeground()
        acquireWakeLock()
        SensorStreamStore.setRunning(true)

        streamJob = scope.launch {
            try {
                SamsungSensorMonitor(applicationContext).readingFlow().collect { batch ->
                    // The UI only ever shows the newest sample; the whole batch goes
                    // to the phone, which needs the series, not the latest value.
                    batch.lastOrNull()?.let(SensorStreamStore::update)

                    val ok = sender.send(
                        applicationContext,
                        SensorBatch(
                            samples = batch.map { reading ->
                                SensorBundle(
                                    bpm = reading.bpm.toInt(),
                                    hrv = reading.hrv,
                                    spo2 = reading.spo2,
                                    skinTemp = reading.skinTemp,
                                    baevskySI = reading.baevskySI,
                                    edaTonic = reading.edaTonic,
                                    edaScrPerMin = reading.edaScrPerMin,
                                    motionFlag = reading.motionFlag,
                                    timestamp = reading.timestamp
                                )
                            }
                        )
                    )
                    SensorStreamStore.recordSend(ok)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sensor stream ended with error", e)
            } finally {
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Monitoramento de sensores",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("PulseLab")
            .setContentText("Monitorando sensores")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pulselab:sensorStream")
            .also { it.acquire(60 * 60 * 1000L) } // 1 h safety cap
    }

    override fun onDestroy() {
        streamJob?.cancel()
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        SensorStreamStore.setRunning(false)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "br.unioeste.pulselab.wear.action.STOP_SENSOR_STREAM"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SensorStreamService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SensorStreamService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
```

### `android/app/src/main/java/br/unioeste/pulselab/wearable/WearableHeartRateListenerService.kt`
(repo `pulselab-companion`)
```kotlin
package br.unioeste.pulselab.wearable

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WearableHeartRateListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == WearMessagePaths.SENSOR_BATCH) {
            HeartRateEventBus.publish(String(event.data, Charsets.UTF_8))
        }
    }
}
```

### `android/app/src/main/java/br/unioeste/pulselab/wearable/HeartRateEventBus.kt`
(repo `pulselab-companion`)
```kotlin
package br.unioeste.pulselab.wearable

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

// Bridges the WearableListenerService (runs on a binder thread, no React context)
// to WearBridgeModule (collects on its own coroutine scope and emits to JS).
object HeartRateEventBus {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events

    fun publish(rawJson: String) {
        _events.tryEmit(rawJson)
    }
}
```

### `android/app/src/main/java/br/unioeste/pulselab/wearable/WearBridgeModule.kt`
(repo `pulselab-companion`)
```kotlin
package br.unioeste.pulselab.wearable

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val EVENT_HEART_RATE = "onHeartRate"

class WearBridgeModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            HeartRateEventBus.events.collect { rawJson ->
                emitBatch(rawJson)
            }
        }
    }

    override fun getName(): String = "WearBridge"

    /**
     * Unwraps one batch message into individual JS events.
     *
     * The watch sends one message per sensor burst (`{"samples":[...]}`) because
     * with the screen dozing the platform withholds delivery and hands it up to 66
     * samples at once — one Data Layer message each would be a burst of ~66 in
     * under a second. Unwrapping here keeps that entirely off the JS side: the
     * `onHeartRate` listener still receives one event per sample, in order, so
     * nothing in TypeScript had to change.
     *
     * Each sample carries its own true acquisition timestamp, so a late batch is
     * still a faithful 1 Hz series — consumers must trust `timestamp`, not arrival
     * time.
     */
    private fun emitBatch(rawJson: String) {
        val samples = try {
            JSONObject(rawJson).optJSONArray("samples") ?: return
        } catch (e: Exception) {
            return
        }

        for (i in 0 until samples.length()) {
            samples.optJSONObject(i)?.let(::emit)
        }
    }

    private fun emit(json: JSONObject) {
        // Field names mirror br.unioeste.pulselab.wear.model.SensorBundle on the watch.
        // Absent/null stays absent on the JS side (undefined) rather than becoming
        // 0 — a missing sensor reading and a reading of zero are not the same
        // thing, and the composite stress index has to tell them apart.
        val params = Arguments.createMap().apply {
            putDouble("bpm", json.optDouble("bpm"))
            if (json.hasValue("hrv")) putInt("hrv", json.getInt("hrv"))
            if (json.hasValue("spo2")) putInt("spo2", json.getInt("spo2"))
            if (json.hasValue("skinTemp")) putDouble("skinTemp", json.getDouble("skinTemp"))
            if (json.hasValue("baevskySI")) putDouble("baevskySI", json.getDouble("baevskySI"))
            if (json.hasValue("edaTonic")) putDouble("edaTonic", json.getDouble("edaTonic"))
            if (json.hasValue("edaScrPerMin")) putInt("edaScrPerMin", json.getInt("edaScrPerMin"))
            putBoolean("motionFlag", json.optBoolean("motionFlag", false))
            putDouble("timestamp", json.optDouble("timestamp"))
        }

        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(EVENT_HEART_RATE, params)
    }

    /** True when the key is present and not JSON null — i.e. carries a real value. */
    private fun JSONObject.hasValue(key: String): Boolean = has(key) && !isNull(key)

    // Required by NativeEventEmitter on Android, even with no-op bodies.
    @ReactMethod
    fun addListener(eventName: String) {}

    @ReactMethod
    fun removeListeners(count: Int) {}

    override fun invalidate() {
        scope.cancel()
        super.invalidate()
    }
}
```

---

## 2. Respostas

### 2.1 — Onde `hrv` (RMSSD) e `baevskySI` são calculados; o IBI bruto sobrevive?

Ambos são calculados **no relógio**, dentro do callback do `hrListener`, antes do envio:
- `SamsungSensorMonitor.kt:152` — `val rmssd = ibiWindow.currentRmssd()`
- `SamsungSensorMonitor.kt:153` — `val si = ibiWindow.currentStressIndex()`

`currentRmssd()` (`Hrv.kt:227`) chama `rmssdOverRuns(currentRuns())` (`Hrv.kt:78`), que soma diferenças sucessivas ao quadrado sobre as runs da janela. `currentStressIndex()` (`Hrv.kt:265`) delega a `BaevskyStressIndex.compute(currentIntervals())` — não lido nesta auditoria, mas a chamada em si confirma que também consome `IbiWindow.currentIntervals()`.

**A lista de IBI bruta (antes da filtragem) não sobrevive.** O que existe:
- A variável `ibiList` em `SamsungSensorMonitor.kt:104` (`dp.getValue(ValueKey.HeartRateSet.IBI_LIST)`) é local ao callback `onDataReceived`. Ela é impressa uma vez em `Log.d` (`SamsungSensorMonitor.kt:120-123`) — que é efêmero (Logcat) — e depois só é percorrida (`forEachIndexed`, `:140`) para alimentar `ibiWindow.add(...)` ou `ibiWindow.noteDropped()`. Quando o callback retorna, `ibiList` sai de escopo. Nenhum outro arquivo tem acesso a ela.
- O que **sobrevive**, mas só dentro de `IbiWindow` (`Hrv.kt:120`), é a sequência de intervalos **já filtrados/aceitos**: `samples: ArrayDeque<Sample>` (`Hrv.kt:125`), exposta via `currentIntervals()` (`Hrv.kt:262`, retorna `samples.map { it.ibiMs }`) e `currentRuns()` (`Hrv.kt:238`). Mas essa sequência:
  - é uma janela deslizante de 60 s (`DEFAULT_WINDOW_MS`, `Hrv.kt:280`) — intervalos mais antigos que isso são removidos (`Hrv.kt:204-207`, `removeFirst()`);
  - contém só os intervalos que passaram pelos filtros de plausibilidade e pela regra de Malik (`Hrv.kt:175-209`) — os intervalos rejeitados nunca têm seu valor armazenado, só são contados (`rejected++`, `Hrv.kt:177`/`188`) e disparam `noteDropped()` — o valor numérico do intervalo rejeitado morre no mesmo callback que o recebeu;
  - a instância de `IbiWindow` em si é uma variável local dentro do `callbackFlow` de `readingFlow()` (`SamsungSensorMonitor.kt:79`) — não é exposta fora de `SamsungSensorMonitor.kt`. Nenhum outro arquivo (nem `SensorStreamService.kt`, nem o payload) tem acesso a `currentIntervals()`/`currentRuns()`.

Resumindo: existe, sim, acesso à sequência de IBI (filtrada, não bruta) em algum ponto da cadeia — mas só dentro da janela de 60 s, só dentro de `IbiWindow`, e nunca exposta além de `SamsungSensorMonitor.kt`. O dado verdadeiramente bruto (pré-filtro) morre dentro do próprio callback que o recebe, sem sair de `SamsungSensorMonitor.kt`.

### 2.2 — `edaTonic`/`edaScrPerMin` têm tracker ativo?

**Não.** Busquei por `EdaWindow` em `SamsungSensorMonitor.kt` — nenhuma ocorrência. Não há `edaListener`, não há `edaTracker`, não há instanciação de `HealthTrackerType.EDA_CONTINUOUS` em nenhum lugar do arquivo — a única menção a EDA em `SamsungSensorMonitor.kt` é em comentário (`:62-64`), explicando por que não há tracker: "EDA_CONTINUOUS is absent from this hardware's capability list, so the tracker was never anything but a warning in the log."

No próprio `SensorReading(...)` que é montado e enviado (`SamsungSensorMonitor.kt:165-178`), os campos são hardcoded:
```kotlin
edaTonic = null,
edaScrPerMin = null,
```
com o comentário explícito (`:171-174`): "Always null on this hardware — kept in the payload so the phone contract does not change if a watch with EDA shows up."

`EdaWindow` (`EdaFeatures.kt`) existe e é funcional — tem `add()`, `tonicLevel()`, `scrPerMinute()`, testado por `EdaFeaturesTest.kt` — mas **não é instanciado nem chamado em nenhum lugar do fluxo de produção**. É código morto no caminho de streaming: existe, compila, tem teste unitário próprio, mas nada no `SamsungSensorMonitor`/`SensorStreamService` cria um `EdaWindow` ou chama `.add()` nele. Confirma exatamente o que o próprio comentário do arquivo já dizia ("DORMANT on current hardware... nothing ever calls [add]").

### 2.3 — Granularidade e alinhamento temporal dos campos dentro de uma "reading"

**Não vêm todos do mesmo instante/janela.** Há três regimes distintos dentro da mesma struct `SensorReading`, todos carimbados com um único `timestamp = dp.getTimestamp()` (`SamsungSensorMonitor.kt:177`) — o timestamp do `DataPoint` de HR que disparou aquela emissão específica:

1. **`bpm`, `hrv`, `baevskySI` — genuinamente alinhados entre si.** Calculados na mesma iteração do loop, sobre o mesmo `dp` de HR: `hr` vem direto de `dp.getValue(...)` (`:102`), e `rmssd`/`si` vêm de `ibiWindow.currentRmssd()`/`currentStressIndex()` (`:152-153`), que refletem a janela de 60 s que termina *neste* `dp`. Esses três são o único subconjunto onde "mesmo timestamp" é literalmente verdade.

2. **`motionFlag` — estado lido no momento da emissão, não sincronizado ao timestamp do HR.** `motionGate.isMoving()` (`:154`) consulta o estado atual de `MotionGate`, que por sua vez é alimentado por um listener de acelerômetro **separado e assíncrono** (`accelListener`, `:225-241`), cuja própria janela é de 1 s (`MotionGate.DEFAULT_WINDOW_MS`, `MotionGate.kt:61`). Não há nenhuma junção por timestamp entre o `dp` do HR e as amostras do acelerômetro — é "o que `MotionGate` sabe agora", possivelmente de até ~1 s de defasagem do instante real do `dp.getTimestamp()` do HR.

3. **`spo2`, `skinTemp` — valores "mais recentes conhecidos", de taxas muito mais lentas e dessincronizadas.** `latestSpo2` e `latestSkinTemp` (`:84-85`) são atualizados por listeners próprios (`spo2Listener`, `skinTempListener`) que rodam em cadências completamente diferentes: SpO2 é on-demand, rearmado a cada 5 minutos (`SPO2_REARM_INTERVAL_MS`, `:39`), cada medição levando ~30 s; skin temperature é contínuo mas a uma taxa não documentada aqui como sendo 1 Hz. Esses dois valores são "o último valor válido que chegou, seja lá quando foi" — carregados adiante (*carry-forward*) e anexados à emissão de HR corrente, **sem qualquer registro de quão antigos são** no momento em que entram no `SensorReading`. Um `SensorReading` com `timestamp=T` pode carregar um `spo2` medido minutos antes de `T`.

**Estratégia de merge:** não há buffer com múltiplos candidatos nem interpolação — é sempre "o último valor conhecido de cada canal lento, no momento em que o canal rápido (HR) dispara uma emissão" (last-value-carried-forward), com um único campo `timestamp` compartilhado que só é literalmente verdadeiro para `bpm`/`hrv`/`baevskySI`.

### 2.4 — Cadência de emissão e tamanho de lote

A emissão é **por evento do SDK**, não por temporizador fixo. `SensorStreamService.onStartCommand` (`:64`) faz `collect` sobre `SamsungSensorMonitor(...).readingFlow()`, e cada elemento desse `Flow<List<SensorReading>>` corresponde a exatamente **um callback `hrListener.onDataReceived(dataPoints)`** (`SamsungSensorMonitor.kt:94`) — o comentário no próprio código é explícito: "this callback IS the platform's burst boundary" (`:96-98`).

O tamanho do lote é **variável**, ditado pela plataforma: com a tela ligada, tipicamente 1 amostra por callback (~1 Hz); com a tela apagada, a entrega é retida e liberada em rajadas — medido entre 14 e 66 amostras por rajada (SENSORS.md §4.1, não citado aqui como arquivo lido nesta rodada, mas confirmado pelo comentário em `SamsungSensorMonitor.kt:48` e `SensorBundle.kt:27-29`, ambos lidos em auditorias anteriores). `SensorStreamService.kt:69-86` envia **um `SensorBatch` por elemento do Flow** — ou seja, um `SensorBatch`/mensagem por callback do SDK, com `samples` de tamanho igual ao `batch` daquele callback. Não há agregação adicional nem divisão de lotes no relógio: a fronteira de lote do SDK é preservada 1:1 até a mensagem Data Layer.

### 2.5 — `bpm: Double` (SensorReading) → `bpm: Int` (SensorBundle): onde e como?

A conversão acontece em `SensorStreamService.kt:74`:
```kotlin
bpm = reading.bpm.toInt(),
```
dentro do `batch.map { reading -> SensorBundle(...) }` (`:72-84`).

É **truncamento, não arredondamento** — `Double.toInt()` em Kotlin trunca em direção a zero, não arredonda. Isso contrasta com o tratamento dado ao `hrv` alguns arquivos antes: em `SamsungSensorMonitor.kt:167`, `hrv = rmssd?.roundToInt()` usa `roundToInt()` explicitamente. Ou seja, o próprio código base já demonstra conhecer a diferença entre truncar e arredondar (usa `roundToInt()` para `hrv`), mas para `bpm` usa `toInt()` sem arredondamento em `SensorStreamService.kt:74`.

Não há nenhum comentário em `SensorStreamService.kt` explicando ou justificando essa conversão — não é uma decisão documentada. Não há como confirmar pela auditoria se é intencional ("BPM já vem como inteiro do sensor, então a parte fracionária de `Double` é ruído sem sentido a arredondar") ou um descuido de tipo — o código não registra a intenção de nenhuma das duas formas.

### 2.6 — `SensorConnectionManager`: superfície de API completa

Toda a API pública, listada integralmente (a classe tem exatamente isto, nada mais):

- `connect()` (`:50-53`) — instancia `HealthTrackingService(connectionListener, appContext)` e chama `connectService()`. Sem retorno.
- `disconnect()` (`:55-58`) — chama `service?.disconnectService()` e zera `service`.
- `supportedTypes(): List<HealthTrackerType>` (`:65-66`) — lê `service?.trackingCapability?.supportHealthTrackerTypes`, retorna lista vazia se não conectado.
- `isSupported(type: HealthTrackerType): Boolean` (`:68`) — `type in supportedTypes()`.
- `tracker(type: HealthTrackerType): HealthTracker?` (`:71`) — `service?.getHealthTracker(type)`.
- `var onConnected: (() -> Unit)?` (`:26`) — callback público, setável, disparado dentro do `ConnectionListener.onConnectionSuccess()` interno (`:32-35`).
- `var onError: ((HealthTrackerException) -> Unit)?` (`:29`) — idem, disparado em `onConnectionFailed(e)` (`:41-47`).

**Acoplamento:** a classe é **fortemente acoplada ao SDK Samsung em toda a sua superfície pública**, não só internamente. Os tipos `HealthTrackerType`, `HealthTracker`, `HealthTrackingService`, `HealthTrackerException`, `ConnectionListener` — todos do pacote `com.samsung.android.service.health.tracking.*` — aparecem diretamente em assinaturas públicas: `tracker(type: HealthTrackerType): HealthTracker?` tem parâmetro **e** retorno em vocabulário Samsung; `onError` é tipado em `HealthTrackerException`, que é uma exceção específica do SDK Samsung, não uma abstração genérica de erro. Não há nenhuma camada de tradução — quem chama `SensorConnectionManager` já está, necessariamente, falando a língua do SDK Samsung.

Consequência direta para a pergunta que motivou o pedido: um `SensorProvider` novo, genérico o suficiente para envolver também Health Services ou um provedor de replay, **não pode simplesmente envolver `SensorConnectionManager` como está** — teria que ficar ao lado dela (uma implementação `SensorProvider` para Samsung que internamente usa `SensorConnectionManager` como está hoje, e outras implementações — Health Services, replay — que não a tocam) em vez de generalizá-la por cima, porque generalizar exigiria mudar sua própria assinatura pública, que hoje devolve e recebe tipos do SDK Samsung diretamente.
