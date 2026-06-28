package br.unioeste.pulselab.wear.data.health

import android.util.Log
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import br.unioeste.pulselab.wear.data.wearable.WearDataSender
import br.unioeste.pulselab.wear.model.HeartRateReading
import br.unioeste.pulselab.wear.model.SensorBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "PassiveDataService"

/**
 * Receives batched heart-rate data from Health Services and forwards it to the
 * phone — even when the app UI is backgrounded or the process was restarted by
 * the system. This is the component that makes background monitoring a real
 * feature: Health Services binds to it independently of the Activity/ViewModel.
 *
 * Note: passive heart rate is delivered in batches at a lower cadence than a
 * foreground sensor stream, so on-screen updates are less frequent than the
 * simulated/foreground path. Only BPM is available — HRV/SpO2/stress are null.
 */
class PassiveDataService : PassiveListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sender = WearDataSender()

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        // A batch may contain several samples; take the most recent valid one.
        val heartRate = dataPoints.getData(DataType.HEART_RATE_BPM)
            .lastOrNull { it.value > 0 } ?: return

        val reading = HeartRateReading(
            bpm = heartRate.value,
            hrv = null,
            spo2 = null,
            stressIndex = null,
            isSimulated = false
        )
        PassiveHeartRateStore.update(reading)
        Log.d(TAG, "Passive HR received — BPM=${reading.bpm.toInt()}")

        scope.launch {
            val bundle = SensorBundle(
                bpm = reading.bpm.toInt(),
                hrv = null,
                spo2 = null,
                stressIndex = null,
                timestamp = reading.timestamp,
                isSimulated = false
            )
            val ok = sender.send(applicationContext, bundle)
            PassiveHeartRateStore.recordSend(ok)
            Log.d(TAG, "Forwarded passive reading to phone — reachable=$ok")
        }
    }

    override fun onPermissionLost() {
        // Body sensors permission was revoked; passive HR will stop arriving.
        Log.w(TAG, "Body sensors permission lost — passive heart rate stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
