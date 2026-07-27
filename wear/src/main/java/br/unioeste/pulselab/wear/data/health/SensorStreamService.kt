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
import br.unioeste.pulselab.wear.data.wearable.WearDataSender
import br.unioeste.pulselab.wear.model.SensorBatch
import br.unioeste.pulselab.wear.model.SensorBundle
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
 * Foreground service that owns the Samsung Health Sensor SDK session so
 * monitoring survives the screen turning off and the Activity being destroyed.
 * The trackers used by [SamsungSensorMonitor] stop delivering when their
 * process loses foreground standing — running them inside a
 * foregroundServiceType="health" service keeps the stream alive.
 *
 * Data path: SamsungSensorMonitor → [SensorStreamStore] (UI mirror) →
 * [WearDataSender] (phone). Same bundle, same transport as every other source.
 */
class SensorStreamService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sender = WearDataSender()
    private var streamJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (streamJob?.isActive == true) return START_STICKY

        startAsForeground()
        acquireWakeLock()
        SensorStreamStore.setRunning(true)

        streamJob = scope.launch {
            try {
                SamsungSensorMonitor(applicationContext).readingFlow().collect { batch ->
                    // The UI only ever shows the newest sample; the whole batch goes
                    // to the phone, which needs the series, not the latest value.
                    batch.lastOrNull()?.let(SensorStreamStore::update)

                    val ok = sender.send(
                        applicationContext,
                        SensorBatch(
                            samples = batch.map { reading ->
                                SensorBundle(
                                    bpm = reading.bpm.toInt(),
                                    hrv = reading.hrv,
                                    spo2 = reading.spo2,
                                    skinTemp = reading.skinTemp,
                                    baevskySI = reading.baevskySI,
                                    edaTonic = reading.edaTonic,
                                    edaScrPerMin = reading.edaScrPerMin,
                                    motionFlag = reading.motionFlag,
                                    timestamp = reading.timestamp
                                )
                            }
                        )
                    )
                    SensorStreamStore.recordSend(ok)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sensor stream ended with error", e)
            } finally {
                stopSelf()
            }
        }
        return START_STICKY
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

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SensorStreamService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SensorStreamService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
