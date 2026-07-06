package br.unioeste.pulselab.wear.data.health.debug

import android.util.Log
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import br.unioeste.pulselab.wear.data.health.SensorConnectionManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "MfBiaTracker"

/** One multi-frequency BIA sample, mapped from [ValueKey.MfBiaSet]. */
data class MfBiaReading(
    val impedanceMagnitude5k: Float?,
    val impedancePhase5k: Float?,
    val impedanceMagnitude10k: Float?,
    val impedancePhase10k: Float?,
    val impedanceMagnitude50k: Float?,
    val impedancePhase50k: Float?,
    val impedanceMagnitude250k: Float?,
    val impedancePhase250k: Float?,
    val progress: Float?,
    val status: Int?,
    val timestamp: Long
)

/**
 * Reads multi-frequency BIA as a single triggered measurement
 * ([HealthTrackerType.MF_BIA_ON_DEMAND]) — the SDK has no continuous variant
 * for this type. Callers should always trigger this explicitly (never
 * auto-start); the flow completes after the first reading.
 */
class MfBiaTracker : SensorTracker<MfBiaReading> {

    override val trackerType: HealthTrackerType = HealthTrackerType.MF_BIA_ON_DEMAND

    override val isContinuous: Boolean = false

    override fun readingFlow(connection: SensorConnectionManager): Flow<MfBiaReading> =
        callbackFlow {
            if (!isSupported(connection)) {
                Log.w(TAG, "$trackerType not supported on this device")
                close()
                return@callbackFlow
            }

            val listener = object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: List<DataPoint>) {
                    for (dp in dataPoints) {
                        trySend(
                            MfBiaReading(
                                impedanceMagnitude5k = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_5K),
                                impedancePhase5k = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_5K),
                                impedanceMagnitude10k = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_10K),
                                impedancePhase10k = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_10K),
                                impedanceMagnitude50k = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_50K),
                                impedancePhase50k = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_50K),
                                impedanceMagnitude250k = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_250K),
                                impedancePhase250k = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_250K),
                                progress = dp.getValue(ValueKey.MfBiaSet.PROGRESS),
                                status = dp.getValue(ValueKey.MfBiaSet.STATUS),
                                timestamp = dp.getTimestamp()
                            )
                        )
                        close()
                        return
                    }
                }

                override fun onFlushCompleted() {}
                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "$trackerType tracker error: $error")
                    close()
                }
            }

            val tracker = connection.tracker(trackerType)?.also { it.setEventListener(listener) }

            awaitClose { tracker?.unsetEventListener() }
        }
}
