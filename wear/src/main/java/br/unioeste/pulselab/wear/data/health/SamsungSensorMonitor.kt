package br.unioeste.pulselab.wear.data.health

import android.content.Context
import android.util.Log
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import br.unioeste.pulselab.wear.model.HeartRateReading
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
// was required (handoff §5/§10).
private const val HR_STATUS_VALID = 1        // confirmed: reliable HR + IBI
private const val HR_STATUS_OFF_BODY = -3    // watch off the wrist (handoff §9)
private const val SPO2_STATUS_COMPLETED = 2  // confirmed: on-demand measurement finished
private const val SKIN_TEMP_STATUS_VALID = 0 // confirmed: 0 == valid (NOT 1)

// SpO2 is on-demand (one ~30 s measurement per arm), so re-arm periodically to
// keep the value fresh while the Sensor source is active.
private const val SPO2_REARM_INTERVAL_MS = 60_000L

/**
 * Foreground monitor backed by the Samsung Health Sensor SDK. Unlike Jetpack
 * Health Services, this gives us IBI (→ RMSSD/HRV), SpO2 and skin temperature.
 *
 * Exposes a single [Flow] of [HeartRateReading] so it slots into the existing
 * pipeline: the ViewModel's foreground collector already displays each reading
 * and forwards it to the phone via WearDataSender (handoff §8 — one sender, one
 * payload, no new transport).
 *
 * Cadence model: HEART_RATE_CONTINUOUS (~1 Hz) is the driver. SpO2 (on-demand)
 * and skin temperature (continuous, slower) update shared "latest" values that
 * ride along on the next HR emission.
 */
class SamsungSensorMonitor(context: Context) {

    private val appContext = context.applicationContext

    /**
     * @param windowMs RMSSD sliding-window length (handoff §7, configurable).
     */
    fun readingFlow(
        windowMs: Long = IbiWindow.DEFAULT_WINDOW_MS,
        spo2RearmMs: Long = SPO2_REARM_INTERVAL_MS
    ): Flow<HeartRateReading> =
        callbackFlow {
            val connection = SensorConnectionManager(appContext)
            val ibiWindow = IbiWindow(windowMs)

            // Secondary metrics updated by their own trackers; attached to the
            // next HR emission. Single-threaded callbacks, so plain vars are fine.
            var latestSpo2: Int? = null
            var latestSkinTemp: Double? = null

            var hrTracker: HealthTracker? = null
            var spo2Tracker: HealthTracker? = null
            var skinTempTracker: HealthTracker? = null
            var spo2Job: Job? = null

            val hrListener = object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: List<DataPoint>) {
                    for (dp in dataPoints) {
                        val hr = dp.getValue(ValueKey.HeartRateSet.HEART_RATE)
                        val hrStatus = dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)
                        val ibiList = dp.getValue(ValueKey.HeartRateSet.IBI_LIST) ?: emptyList()
                        val ibiStatus = dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) ?: emptyList()

                        // Raw log so on-device validation can confirm IBI + status semantics.
                        Log.d(TAG, "HR=$hr status=$hrStatus ibi=$ibiList ibiStatus=$ibiStatus window=${ibiWindow.size}")

                        if (hrStatus == HR_STATUS_OFF_BODY) continue  // off-wrist → ignore

                        // Keep only valid IBIs, else RMSSD explodes (handoff §6.1 / §11).
                        ibiList.forEachIndexed { i, ibi ->
                            if (ibiStatus.getOrNull(i) == IBI_STATUS_VALID && ibi > 0) {
                                ibiWindow.add(ibi.toDouble(), dp.getTimestamp())
                            }
                        }

                        if (hr != null && hr > 0 && hrStatus == HR_STATUS_VALID) {
                            val rmssd = ibiWindow.currentRmssd()
                            Log.d(TAG, "RMSSD=${rmssd?.let { "%.1f".format(it) } ?: "—"} ms over ${ibiWindow.size} IBIs")
                            trySend(
                                HeartRateReading(
                                    bpm = hr.toDouble(),
                                    hrv = rmssd?.roundToInt(),
                                    spo2 = latestSpo2,
                                    stressIndex = null,
                                    skinTemp = latestSkinTemp,
                                    timestamp = dp.getTimestamp(),
                                    isSimulated = false
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
                        Log.d(TAG, "SpO2 status=$status value=$spo2")
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
                        Log.d(TAG, "SkinTemp status=$status temp=$temp")
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

            connection.onConnected = {
                val supported = connection.supportedTypes()

                // Capability check is the definitive gate (handoff §5): only start
                // trackers this watch actually reports.
                if (HealthTrackerType.HEART_RATE_CONTINUOUS in supported) {
                    hrTracker = connection.tracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
                        ?.also { it.setEventListener(hrListener) }
                } else {
                    Log.w(TAG, "HEART_RATE_CONTINUOUS not supported on this device")
                }

                if (HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS in supported) {
                    skinTempTracker = connection.tracker(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS)
                        ?.also { it.setEventListener(skinTempListener) }
                } else {
                    Log.w(TAG, "SKIN_TEMPERATURE_CONTINUOUS not supported on this device")
                }

                // SpO2 is on-demand (handoff §9): each arm yields one ~30 s measurement,
                // so re-arm on a loop to keep it fresh. Only one on-demand tracker may
                // run at a time. The loop is a child of the flow scope, so it is
                // cancelled automatically when collection stops.
                if (HealthTrackerType.SPO2_ON_DEMAND in supported) {
                    spo2Job = this@callbackFlow.launch {
                        while (isActive) {
                            spo2Tracker?.unsetEventListener()
                            spo2Tracker = connection.tracker(HealthTrackerType.SPO2_ON_DEMAND)
                                ?.also { it.setEventListener(spo2Listener) }
                            Log.d(TAG, "SpO2 on-demand measurement armed")
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
                connection.disconnect()
                Log.d(TAG, "Monitor closed — trackers unset, service disconnected")
            }
        }
}
