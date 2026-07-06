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

private const val TAG = "AccelerometerTracker"

/** One accelerometer sample, mapped from [ValueKey.AccelerometerSet] (no STATUS field in this set). */
data class AccelerometerReading(val x: Int?, val y: Int?, val z: Int?, val timestamp: Long)

/**
 * Reads raw accelerometer axes. The SDK exposes both [HealthTrackerType.ACCELEROMETER]
 * and [HealthTrackerType.ACCELEROMETER_CONTINUOUS]; per US-004 both are treated as
 * continuous-type here (no on-demand trigger), defaulting to the CONTINUOUS variant.
 */
class AccelerometerTracker(private val useContinuousType: Boolean = true) :
    SensorTracker<AccelerometerReading> {

    override val trackerType: HealthTrackerType =
        if (useContinuousType) HealthTrackerType.ACCELEROMETER_CONTINUOUS else HealthTrackerType.ACCELEROMETER

    override val isContinuous: Boolean = true

    override fun readingFlow(connection: SensorConnectionManager): Flow<AccelerometerReading> =
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
                            AccelerometerReading(
                                x = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X),
                                y = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y),
                                z = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z),
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
