package br.unioeste.pulselab.wear.data.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import br.unioeste.pulselab.wear.model.HeartRateReading
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge

// ─── SIMULATION SWITCH ──────────────────────────────────────────────────────
// true  → simulated data + real sensor run simultaneously (for side-by-side testing)
// false → real sensor only
private const val SIMULATE = true
// ────────────────────────────────────────────────────────────────────────────

private const val TAG = "HeartRateRepository"

class HeartRateRepository(private val context: Context) {

    companion object {
        // In simulate mode the permission gate is skipped — real sensor flow handles
        // SecurityException internally if BODY_SENSORS hasn't been granted yet.
        val requiresSensorPermission = !SIMULATE
    }

    fun heartRateFlow(): Flow<HeartRateReading> {
        return if (SIMULATE) merge(simulatedHeartRateFlow(), realHeartRateFlow())
        else realHeartRateFlow()
    }

    // Generates stable fake BPM data for pipeline testing.
    // Variance ±1 BPM per tick keeps the numbers readable on screen.
    private fun simulatedHeartRateFlow(): Flow<HeartRateReading> = flow {
        var bpm = 72.0
        while (true) {
            bpm = (bpm + (Math.random() - 0.5) * 2).coerceIn(65.0, 80.0)
            emit(
                HeartRateReading(
                    bpm = bpm,
                    hrv = (40..60).random(),
                    spo2 = (97..99).random(),
                    stressIndex = (20..40).random(),
                    isSimulated = true
                )
            )
            delay(1000L)
        }
    }

    // Reads BPM from the standard Android heart rate sensor (TYPE_HEART_RATE).
    // HRV, SpO2, and stress are not available via this API and will be null.
    private fun realHeartRateFlow(): Flow<HeartRateReading> = callbackFlow {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

        if (heartRateSensor == null) {
            Log.e(TAG, "No heart rate sensor found on this device")
            close()
            return@callbackFlow
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val bpm = event.values[0].toDouble()
                if (bpm > 0) {
                    trySend(
                        HeartRateReading(
                            bpm = bpm,
                            hrv = null,
                            spo2 = null,
                            stressIndex = null,
                            isSimulated = false
                        )
                    )
                }
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                Log.d(TAG, "Heart rate sensor accuracy changed: $accuracy")
            }
        }

        val registered = try {
            sensorManager.registerListener(listener, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL)
        } catch (e: SecurityException) {
            Log.w(TAG, "BODY_SENSORS permission denied — real sensor unavailable", e)
            close()
            return@callbackFlow
        }

        if (!registered) {
            Log.w(TAG, "SensorManager.registerListener returned false — sensor unavailable")
            close()
            return@callbackFlow
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }
}
