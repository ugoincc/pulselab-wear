package br.unioeste.pulselab.wear.data.health

import android.content.Context
import android.util.Log
import androidx.concurrent.futures.await
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import br.unioeste.pulselab.wear.model.HeartRateReading
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "LiveMeasureMonitor"

/**
 * Live, foreground heart rate via Health Services [MeasureClient]. Delivers
 * per-second updates while the app is in the foreground and auto-stops when it
 * is backgrounded (that's by design — use PassiveMonitoringClient for
 * background). MeasureClient only supports HEART_RATE_BPM; HRV/SpO2/skin
 * temp/stress are not available through this API.
 */
class LiveMeasureMonitor(context: Context) {

    private val measureClient =
        HealthServices.getClient(context.applicationContext).measureClient

    suspend fun supportsHeartRate(): Boolean =
        DataType.HEART_RATE_BPM in measureClient.getCapabilitiesAsync().await().supportedDataTypesMeasure

    fun heartRateFlow(): Flow<HeartRateReading> = callbackFlow {
        val callback = object : MeasureCallback {
            override fun onAvailabilityChanged(
                dataType: DeltaDataType<*, *>,
                availability: Availability
            ) {
                if (availability is DataTypeAvailability) {
                    // e.g. ACQUIRING, AVAILABLE, UNAVAILABLE (off-wrist)
                    Log.d(TAG, "HR availability changed: $availability")
                }
            }

            override fun onDataReceived(data: DataPointContainer) {
                val latest = data.getData(DataType.HEART_RATE_BPM)
                    .lastOrNull { it.value > 0 } ?: return
                trySend(
                    HeartRateReading(
                        bpm = latest.value,
                        hrv = null,
                        spo2 = null,
                        stressIndex = null,
                        isSimulated = false
                    )
                )
            }
        }

        measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
        awaitClose {
            measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback)
        }
    }
}
