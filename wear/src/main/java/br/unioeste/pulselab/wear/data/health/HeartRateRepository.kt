package br.unioeste.pulselab.wear.data.health

import br.unioeste.pulselab.wear.model.HeartRateReading
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Simulated heart-rate source used for the "Fake" data toggle (foreground only).
 *
 * Real heart rate is read in the background via Health Services — see
 * [PassiveMonitoringManager] and [PassiveDataService] — not through this class.
 */
class HeartRateRepository {

    companion object {
        // Passive heart-rate monitoring still requires the BODY_SENSORS runtime
        // permission, so the permission gate in the UI remains in effect.
        const val requiresSensorPermission = true
    }

    fun simulatedFlow(): Flow<HeartRateReading> = flow {
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
}
