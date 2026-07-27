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
 * PROVISIONAL: EDA has never been read on-device, so its "valid" status code is
 * assumed to follow the skin-temperature convention (0 == valid). Every EDA sample
 * is logged with its raw status so the first on-device run can confirm or correct
 * this — the same validation that turned up skin temperature's 0 (not 1).
 */
private const val EDA_STATUS_VALID = 0

// SpO2 is on-demand (one ~30 s measurement per arm), so re-arm periodically to
// keep the value fresh.
private const val SPO2_REARM_INTERVAL_MS = 60_000L

/**
 * The watch's single source of biometrics, backed by the Samsung Health Sensor
 * SDK. Emits one [SensorReading] per heart-rate sample (~1 Hz), carrying every
 * channel the watch can read.
 *
 * Two of the six values are computed here rather than read: RMSSD ([IbiWindow])
 * and the Baevsky Stress Index ([BaevskyStressIndex]), both over the same window
 * of filtered IBI intervals. The accelerometer contributes no value of its own —
 * it only sets [SensorReading.motionFlag] through [MotionGate], so the phone can
 * exclude movement-contaminated windows from the composite index.
 *
 * Cadence model: HEART_RATE_CONTINUOUS (~1 Hz) is the driver. The slower or
 * higher-rate channels (SpO2, skin temperature, EDA, accelerometer) update shared
 * "latest" state that rides along on the next HR emission.
 */
class SamsungSensorMonitor(context: Context) {

    private val appContext = context.applicationContext

    /**
     * @param windowMs RMSSD/SI sliding-window length.
     */
    fun readingFlow(
        windowMs: Long = IbiWindow.DEFAULT_WINDOW_MS,
        spo2RearmMs: Long = SPO2_REARM_INTERVAL_MS
    ): Flow<SensorReading> =
        callbackFlow {
            val connection = SensorConnectionManager(appContext)
            val ibiWindow = IbiWindow(windowMs)
            val edaWindow = EdaWindow()
            val motionGate = MotionGate()

            // Secondary metrics updated by their own trackers; attached to the
            // next HR emission. Single-threaded callbacks, so plain vars are fine.
            var latestSpo2: Int? = null
            var latestSkinTemp: Double? = null

            var hrTracker: HealthTracker? = null
            var spo2Tracker: HealthTracker? = null
            var skinTempTracker: HealthTracker? = null
            var edaTracker: HealthTracker? = null
            var accelTracker: HealthTracker? = null
            var spo2Job: Job? = null

            val hrListener = object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: List<DataPoint>) {
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
                                    "(${ibiWindow.rejected} descartados), " +
                                    "motion=$moving p2p=${motionGate.peakToPeak()?.let { "%.0f".format(it) } ?: "—"}"
                            )

                            trySend(
                                SensorReading(
                                    bpm = hr.toDouble(),
                                    hrv = rmssd?.roundToInt(),
                                    spo2 = latestSpo2,
                                    skinTemp = latestSkinTemp,
                                    baevskySI = si,
                                    edaTonic = edaWindow.tonicLevel(),
                                    edaScrPerMin = edaWindow.scrPerMinute(),
                                    motionFlag = moving,
                                    timestamp = dp.getTimestamp()
                                )
                            )
                        }
                    }
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

            val edaListener = object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: List<DataPoint>) {
                    for (dp in dataPoints) {
                        val status = dp.getValue(ValueKey.EdaSet.STATUS)
                        val conductance = dp.getValue(ValueKey.EdaSet.SKIN_CONDUCTANCE)
                        // Logged unconditionally: EDA_STATUS_VALID is still a guess.
                        Log.d(TAG, "EDA status=$status skinConductance=$conductance")
                        if (status == EDA_STATUS_VALID && conductance != null && conductance > 0f) {
                            edaWindow.add(conductance.toDouble(), dp.getTimestamp())
                        }
                    }
                }

                override fun onFlushCompleted() {}
                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "EDA tracker error: $error")
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
                edaTracker = startContinuous(HealthTrackerType.EDA_CONTINUOUS, edaListener)
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
                edaTracker?.unsetEventListener()
                accelTracker?.unsetEventListener()
                connection.disconnect()
                Log.d(TAG, "Monitor closed — trackers unset, service disconnected")
            }
        }
}
