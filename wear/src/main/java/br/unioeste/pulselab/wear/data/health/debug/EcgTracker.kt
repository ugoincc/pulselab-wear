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

private const val TAG = "EcgTracker"

/** One ECG sample, mapped from [ValueKey.EcgSet]. */
data class EcgReading(
    val leadOff: Int?,
    val sequence: Int?,
    val ecgMv: Float?,
    val maxThresholdMv: Float?,
    val minThresholdMv: Float?,
    val timestamp: Long
)

/**
 * Reads ECG either continuously ([HealthTrackerType.ECG]) or as a single
 * triggered measurement ([HealthTrackerType.ECG_ON_DEMAND]), depending on
 * [onDemand]. On-demand mode completes the flow after the first reading;
 * continuous mode keeps emitting until the collector cancels.
 */
class EcgTracker(private val onDemand: Boolean = false) : SensorTracker<EcgReading> {

    override val trackerType: HealthTrackerType =
        if (onDemand) HealthTrackerType.ECG_ON_DEMAND else HealthTrackerType.ECG

    override val isContinuous: Boolean = !onDemand

    override fun readingFlow(connection: SensorConnectionManager): Flow<EcgReading> =
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
                            EcgReading(
                                leadOff = dp.getValue(ValueKey.EcgSet.LEAD_OFF),
                                sequence = dp.getValue(ValueKey.EcgSet.SEQUENCE),
                                ecgMv = dp.getValue(ValueKey.EcgSet.ECG_MV),
                                maxThresholdMv = dp.getValue(ValueKey.EcgSet.MAX_THRESHOLD_MV),
                                minThresholdMv = dp.getValue(ValueKey.EcgSet.MIN_THRESHOLD_MV),
                                timestamp = dp.getTimestamp()
                            )
                        )
                        if (onDemand) {
                            close()
                            return
                        }
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
