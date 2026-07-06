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

private const val TAG = "EdaTracker"

/** One EDA (electrodermal activity) sample, mapped from [ValueKey.EdaSet]. */
data class EdaReading(val skinConductance: Float?, val status: Int?, val timestamp: Long)

/**
 * Reads skin conductance from [HealthTrackerType.EDA_CONTINUOUS] — the SDK has no
 * bare EDA or on-demand variant, so this tracker always starts/stops with the
 * caller's flow lifecycle.
 */
class EdaTracker : SensorTracker<EdaReading> {

    override val trackerType: HealthTrackerType = HealthTrackerType.EDA_CONTINUOUS

    override val isContinuous: Boolean = true

    override fun readingFlow(connection: SensorConnectionManager): Flow<EdaReading> =
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
                            EdaReading(
                                skinConductance = dp.getValue(ValueKey.EdaSet.SKIN_CONDUCTANCE),
                                status = dp.getValue(ValueKey.EdaSet.STATUS),
                                timestamp = dp.getTimestamp()
                            )
                        )
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
