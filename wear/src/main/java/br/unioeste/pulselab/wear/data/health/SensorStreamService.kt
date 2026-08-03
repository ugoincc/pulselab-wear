package br.unioeste.pulselab.wear.data.health

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import br.unioeste.pulselab.wear.R
import br.unioeste.pulselab.wear.data.provider.CapabilityAnnouncement
import br.unioeste.pulselab.wear.data.provider.ProviderChoice
import br.unioeste.pulselab.wear.data.provider.ProviderSelection
import br.unioeste.pulselab.wear.data.provider.SensorProvider
import br.unioeste.pulselab.wear.data.wearable.WearDataSender
import br.unioeste.pulselab.wear.model.SensorBatch
import br.unioeste.pulselab.wear.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "SensorStreamService"
private const val CHANNEL_ID = "sensor_stream"
private const val NOTIFICATION_ID = 2001

/**
 * Foreground service that owns the sensor session so monitoring survives the
 * screen turning off and the Activity being destroyed. Both real providers stop
 * delivering when their process loses foreground standing — the Samsung SDK
 * trackers and, for a different reason, Health Services' `MeasureClient`, which
 * unregisters itself on backgrounding by design — so running them inside a
 * foregroundServiceType="health" service is what keeps the stream alive.
 *
 * Data path: [SensorProvider] → [SensorStreamStore] (UI mirror) →
 * [WearDataSender] (phone). The service does not know which provider it is
 * collecting from; that is [ProviderSelection]'s business, and the fact that
 * nothing below this line changes when the answer changes is the claim
 * Objetivo 4 makes (cap6-avaliacao.tex).
 */
class SensorStreamService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sender = WearDataSender()
    private var streamJob: Job? = null
    private var streamingProvider: ProviderChoice? = null

    /**
     * Incremented each time a collection is started. A cancelled collection's
     * `finally` runs asynchronously, possibly after its replacement is already
     * running, so "am I still the current stream?" cannot be answered by
     * comparing against [streamJob] without a race — the generation it captured
     * at launch answers it deterministically.
     */
    private var streamGeneration = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // A null Intent means the system restarted us under START_STICKY, so the
        // selection has to come from storage, not from the extra — see
        // ProviderSelection's note on why it is persisted.
        intent?.getStringExtra(EXTRA_PROVIDER)?.let { requested ->
            ProviderSelection.select(applicationContext, ProviderChoice.of(requested))
        }
        val choice = ProviderSelection.selected(applicationContext)

        if (streamJob?.isActive == true) {
            if (streamingProvider == choice) return START_STICKY
            // Switching provider mid-run: tear the old collection down first so
            // its connection closes and its capability announcement stops
            // applying. The phone treats the next announcement as the start of a
            // new provider connection (ESPECIFICACAO §6.3).
            Log.i(TAG, "Switching provider: $streamingProvider → $choice")
            streamJob?.cancel()
        } else {
            startAsForeground()
            acquireWakeLock()
        }

        SensorStreamStore.setRunning(true)
        streamingProvider = choice
        val generation = ++streamGeneration

        streamJob = scope.launch {
            val provider = ProviderSelection.create(applicationContext)
            try {
                // Once per provider connection, not per sample
                // (ESPECIFICACAO §6.3). Sent before collecting so the phone can
                // interpret the first batch it sees, and re-attempted on the
                // first successful batch if it failed: an announcement dropped
                // because the phone was unreachable at start-up would otherwise
                // leave that whole run's samples without a declaration, and the
                // difference between "null because not yet" and "null because
                // never" is exactly what the declaration carries.
                var announced = announce(provider)

                provider.readingFlow().collect { batch ->
                    // The UI only ever shows the newest sample; the whole batch
                    // goes to the phone, which needs the series, not the latest
                    // value.
                    batch.lastOrNull()?.let(SensorStreamStore::update)

                    val ok = sender.send(applicationContext, SensorBatch(samples = batch))
                    SensorStreamStore.recordSend(ok)

                    if (!announced && ok) announced = announce(provider)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sensor stream ended with error", e)
            } finally {
                provider.disconnect()
                // Only tear the service down if this collection is still the
                // current one: a provider switch cancels this job on purpose and
                // the replacement is already starting.
                if (generation == streamGeneration) stopSelf()
            }
        }
        return START_STICKY
    }

    private suspend fun announce(provider: SensorProvider): Boolean {
        val sent = sender.sendCapabilities(
            applicationContext,
            CapabilityAnnouncement(provider.providerId, provider.capabilities())
        )
        if (!sent) {
            Log.w(TAG, "Capability announcement for ${provider.providerId} not delivered — will retry")
        }
        return sent
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Monitoramento de sensores",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("PulseLab")
            .setContentText("Monitorando sensores")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pulselab:sensorStream")
            .also { it.acquire(60 * 60 * 1000L) } // 1 h safety cap
    }

    override fun onDestroy() {
        streamJob?.cancel()
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        SensorStreamStore.setRunning(false)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "br.unioeste.pulselab.wear.action.STOP_SENSOR_STREAM"
        private const val EXTRA_PROVIDER = "br.unioeste.pulselab.wear.extra.PROVIDER"

        /**
         * @param provider which source to collect from. Null keeps whatever was
         *   selected last, which is also what a system-initiated restart gets.
         *   Passing a different one while the service is already streaming
         *   switches it in place — that switch is the acceptance criterion for
         *   Objetivo 4: the provider id and the set of populated fields change,
         *   and nothing downstream of [SensorBatch] does.
         */
        fun start(context: Context, provider: ProviderChoice? = null) {
            val intent = Intent(context, SensorStreamService::class.java)
            provider?.let { intent.putExtra(EXTRA_PROVIDER, it.id) }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SensorStreamService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
