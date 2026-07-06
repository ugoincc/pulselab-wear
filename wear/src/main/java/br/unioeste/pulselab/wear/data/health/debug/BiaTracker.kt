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

private const val TAG = "BiaTracker"

/** One BIA sample, mapped from [ValueKey.BiaSet]. */
data class BiaReading(
    val bodyFatRatio: Float?,
    val bodyFatMass: Float?,
    val totalBodyWater: Float?,
    val skeletalMuscleRatio: Float?,
    val skeletalMuscleMass: Float?,
    val basalMetabolicRate: Float?,
    val progress: Float?,
    val status: Int?,
    val timestamp: Long
)

/**
 * Reads BIA either continuously ([HealthTrackerType.BIA]) or as a single
 * triggered measurement ([HealthTrackerType.BIA_ON_DEMAND]), depending on
 * [onDemand]. Per US-003, callers should always trigger this on-demand (never
 * auto-start); on-demand mode completes the flow after the first reading.
 */
class BiaTracker(private val onDemand: Boolean = true) : SensorTracker<BiaReading> {

    override val trackerType: HealthTrackerType =
        if (onDemand) HealthTrackerType.BIA_ON_DEMAND else HealthTrackerType.BIA

    override val isContinuous: Boolean = !onDemand

    override fun readingFlow(connection: SensorConnectionManager): Flow<BiaReading> =
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
                            BiaReading(
                                bodyFatRatio = dp.getValue(ValueKey.BiaSet.BODY_FAT_RATIO),
                                bodyFatMass = dp.getValue(ValueKey.BiaSet.BODY_FAT_MASS),
                                totalBodyWater = dp.getValue(ValueKey.BiaSet.TOTAL_BODY_WATER),
                                skeletalMuscleRatio = dp.getValue(ValueKey.BiaSet.SKELETAL_MUSCLE_RATIO),
                                skeletalMuscleMass = dp.getValue(ValueKey.BiaSet.SKELETAL_MUSCLE_MASS),
                                basalMetabolicRate = dp.getValue(ValueKey.BiaSet.BASAL_METABOLIC_RATE),
                                progress = dp.getValue(ValueKey.BiaSet.PROGRESS),
                                status = dp.getValue(ValueKey.BiaSet.STATUS),
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
