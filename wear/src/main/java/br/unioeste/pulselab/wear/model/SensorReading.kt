package br.unioeste.pulselab.wear.model

/**
 * Wire schema version (ESPECIFICACAO-protocolo-payload-v1.md §2). Bump this,
 * not the field list in place, when a change removes a field, changes a type,
 * or changes the semantics of an existing field.
 */
const val SENSOR_SCHEMA_VERSION = 1

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
    /** Baevsky Stress Index over the same IBI window, on the raw scale (no sqrt). */
    val baevskySI: Double?,
    /** Tonic skin conductance level (SCL), in microsiemens. */
    val edaTonic: Double?,
    /** Phasic skin-conductance responses per minute. */
    val edaScrPerMin: Int?,
    /** True when the accelerometer saw movement in this window (artefact gate). */
    val motionFlag: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    /** ESPECIFICACAO-protocolo-payload-v1.md §2. */
    val schemaVersion: Int = SENSOR_SCHEMA_VERSION,
    /**
     * Monotonic per [SensorReading] emitted (not per batch), reset to 0 each
     * time the emitting provider's read loop is (re)collected — see
     * ESPECIFICACAO-protocolo-payload-v1.md §5 and the reset-scope decision
     * recorded in [br.unioeste.pulselab.wear.data.health.SamsungSensorMonitor].
     */
    val sequenceNumber: Long,
    /**
     * Epoch ms of when the SpO2 cycle that produced [spo2] actually completed —
     * distinct from [timestamp], which belongs to the HR data point this
     * reading rode along on. Null iff [spo2] is null.
     */
    val spo2MeasuredAt: Long?,
    /** Same idea as [spo2MeasuredAt], for [skinTemp]. Null iff [skinTemp] is null. */
    val skinTempMeasuredAt: Long?,
    /** Which [br.unioeste.pulselab.wear.data.provider.SensorProvider] produced this reading. */
    val providerId: String
)
