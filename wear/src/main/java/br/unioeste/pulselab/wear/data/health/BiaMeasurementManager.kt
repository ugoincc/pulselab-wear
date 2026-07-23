package br.unioeste.pulselab.wear.data.health

import android.content.Context
import android.util.Log
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "BiaMeasurement"

/** Final result of a completed BIA measurement, mapped from [ValueKey.BiaSet]. */
data class BiaResult(
    val bodyFatRatio: Float?,
    val bodyFatMass: Float?,
    val skeletalMuscleRatio: Float?,
    val skeletalMuscleMass: Float?,
    val totalBodyWater: Float?,
    val basalMetabolicRate: Float?,
    val fatFreeMass: Float?,
    val fatFreeRatio: Float?,
    val impedance: Float?,
    val timestamp: Long
)

/** Live state of a guided BIA measurement. */
sealed class BiaMeasureState {
    /** Connecting to the Samsung Health Platform. */
    object Connecting : BiaMeasureState()

    /**
     * Measurement running. [progress] is 0..1; [contactLost] flags a sample whose
     * status indicates bad finger/posture contact so the UI can coach the user.
     */
    data class Measuring(val progress: Float, val contactLost: Boolean) : BiaMeasureState()

    data class Done(val result: BiaResult) : BiaMeasureState()

    data class Failed(val reason: String) : BiaMeasureState()
}

/**
 * Runs one on-demand BIA measurement ([HealthTrackerType.BIA_ON_DEMAND]) as a
 * state flow the guided UI can render step by step.
 *
 * Unlike the debug BiaTracker (which closes on the first sample), this stays
 * subscribed for the whole measurement: BIA delivers a stream of progress
 * samples (~15 s of two-finger contact on the watch keys) and only the final
 * sample carries the body-composition values.
 */
class BiaMeasurementManager(context: Context) {

    private val appContext = context.applicationContext

    fun measure(): Flow<BiaMeasureState> = callbackFlow {
        trySend(BiaMeasureState.Connecting)

        val connection = SensorConnectionManager(appContext)
        var tracker: HealthTracker? = null

        val listener = object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(dataPoints: List<DataPoint>) {
                for (dp in dataPoints) {
                    val status = dp.getValue(ValueKey.BiaSet.STATUS)
                    val rawProgress = dp.getValue(ValueKey.BiaSet.PROGRESS)
                    val bodyFat = dp.getValue(ValueKey.BiaSet.BODY_FAT_RATIO)
                    Log.d(TAG, "BIA sample status=$status progress=$rawProgress bodyFat=$bodyFat")

                    // PROGRESS semantics are undocumented; normalize 0..100 → 0..1.
                    val progress = rawProgress?.let { if (it > 1f) it / 100f else it } ?: 0f

                    if (progress >= 1f && bodyFat != null && bodyFat > 0f) {
                        trySend(
                            BiaMeasureState.Done(
                                BiaResult(
                                    bodyFatRatio = bodyFat,
                                    bodyFatMass = dp.getValue(ValueKey.BiaSet.BODY_FAT_MASS),
                                    skeletalMuscleRatio = dp.getValue(ValueKey.BiaSet.SKELETAL_MUSCLE_RATIO),
                                    skeletalMuscleMass = dp.getValue(ValueKey.BiaSet.SKELETAL_MUSCLE_MASS),
                                    totalBodyWater = dp.getValue(ValueKey.BiaSet.TOTAL_BODY_WATER),
                                    basalMetabolicRate = dp.getValue(ValueKey.BiaSet.BASAL_METABOLIC_RATE),
                                    fatFreeMass = dp.getValue(ValueKey.BiaSet.FAT_FREE_MASS),
                                    fatFreeRatio = dp.getValue(ValueKey.BiaSet.FAT_FREE_RATIO),
                                    impedance = dp.getValue(ValueKey.BiaSet.BODY_IMPEDANCE_MAGNITUDE),
                                    timestamp = dp.getTimestamp()
                                )
                            )
                        )
                        close()
                        return
                    }

                    // Negative status observed on other trackers as invalid contact;
                    // surface it so the UI can tell the user to adjust their fingers.
                    trySend(
                        BiaMeasureState.Measuring(
                            progress = progress,
                            contactLost = status != null && status < 0
                        )
                    )
                }
            }

            override fun onFlushCompleted() {}

            override fun onError(error: HealthTracker.TrackerError) {
                Log.e(TAG, "BIA tracker error: $error")
                trySend(BiaMeasureState.Failed(error.name))
                close()
            }
        }

        connection.onConnected = {
            if (connection.isSupported(HealthTrackerType.BIA_ON_DEMAND)) {
                // getHealthTracker can reject types the capability list reports
                // (seen on Galaxy Watch Ultra: "Invalid HealthTrackerType" for
                // BIA_ON_DEMAND) — fail the flow instead of crashing the app.
                runCatching {
                    tracker = connection.tracker(HealthTrackerType.BIA_ON_DEMAND)
                        ?.also { it.setEventListener(listener) }
                }.onFailure { e ->
                    Log.e(TAG, "Could not obtain BIA tracker", e)
                    trySend(BiaMeasureState.Failed("Medição BIA indisponível neste relógio"))
                    close()
                }
            } else {
                trySend(BiaMeasureState.Failed("BIA não suportado neste relógio"))
                close()
            }
        }

        connection.onError = { e ->
            trySend(BiaMeasureState.Failed("Falha na conexão (código ${e.errorCode})"))
            close()
        }

        connection.connect()

        awaitClose {
            tracker?.unsetEventListener()
            connection.disconnect()
        }
    }
}
