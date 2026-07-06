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

private const val TAG = "SweatLossTracker"

/** One sweat loss sample, mapped from [ValueKey.SweatLossSet]. */
data class SweatLossReading(val sweatLoss: Float?, val status: Int?, val timestamp: Long)

/**
 * Reads sweat loss from [HealthTrackerType.SWEAT_LOSS] — the SDK has no continuous
 * or on-demand variant for this type, so it always starts/stops with the caller's
 * flow lifecycle.
 */
class SweatLossTracker : SensorTracker<SweatLossReading> {

    override val trackerType: HealthTrackerType = HealthTrackerType.SWEAT_LOSS

    override val isContinuous: Boolean = true

    override fun readingFlow(connection: SensorConnectionManager): Flow<SweatLossReading> =
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
                            SweatLossReading(
                                sweatLoss = dp.getValue(ValueKey.SweatLossSet.SWEAT_LOSS),
                                status = dp.getValue(ValueKey.SweatLossSet.STATUS),
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
