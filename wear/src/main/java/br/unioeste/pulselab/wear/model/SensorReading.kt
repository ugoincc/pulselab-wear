package br.unioeste.pulselab.wear.model

/**
 * One second of biometrics, as read on the watch. Every field comes from a real
 * sensor or from maths over real sensor data — there is no simulated source.
 *
 * Heart rate drives the cadence; the slower channels (SpO2, skin temperature,
 * EDA) carry their most recent value forward, and are null until their tracker
 * has delivered a first valid sample.
 */
data class SensorReading(
    val bpm: Double,
    /** RMSSD over the IBI window, in ms. */
    val hrv: Int?,
    val spo2: Int?,
    val skinTemp: Double?,
    /** Baevsky Stress Index over the same IBI window (sqrt-transformed). */
    val baevskySI: Double?,
    /** Tonic skin conductance level (SCL), in microsiemens. */
    val edaTonic: Double?,
    /** Phasic skin-conductance responses per minute. */
    val edaScrPerMin: Int?,
    /** True when the accelerometer saw movement in this window (artefact gate). */
    val motionFlag: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
