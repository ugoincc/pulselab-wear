package br.unioeste.pulselab.wear.data.health

import android.content.Context
import android.util.Log
import androidx.concurrent.futures.await
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig

private const val TAG = "PassiveMonitoringManager"

/**
 * Registers and clears background heart-rate monitoring via Health Services'
 * PassiveMonitoringClient.
 *
 * Registration is persisted by Health Services itself — once [start] is called,
 * [PassiveDataService] keeps receiving data across app backgrounding, process
 * death, and reboot, until [stop] is called. We mirror that intent in
 * SharedPreferences so the UI can show (and let the user end) an active
 * background session after the process has been restarted.
 */
class PassiveMonitoringManager(context: Context) {

    private val appContext = context.applicationContext
    private val client = HealthServices.getClient(appContext).passiveMonitoringClient
    private val prefs =
        appContext.getSharedPreferences("passive_monitoring", Context.MODE_PRIVATE)

    /** True if a background session was started and not yet stopped. */
    val isBackgroundActive: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE, false)

    suspend fun start() {
        val config = PassiveListenerConfig.builder()
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .build()
        client.setPassiveListenerServiceAsync(PassiveDataService::class.java, config).await()
        prefs.edit().putBoolean(KEY_ACTIVE, true).apply()
        Log.d(TAG, "Background heart-rate monitoring registered")
    }

    suspend fun stop() {
        client.clearPassiveListenerServiceAsync().await()
        prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
        PassiveHeartRateStore.reset()
        Log.d(TAG, "Background heart-rate monitoring cleared")
    }

    private companion object {
        const val KEY_ACTIVE = "background_active"
    }
}
