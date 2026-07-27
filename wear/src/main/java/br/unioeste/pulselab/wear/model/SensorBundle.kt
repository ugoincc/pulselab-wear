package br.unioeste.pulselab.wear.model

/**
 * Wire format sent to the phone over the Wear Data Layer on [MessagePaths.SENSOR_DATA],
 * serialised as JSON by Gson. Field names are the contract with the phone app's
 * WearBridge — renaming one is a cross-repo breaking change.
 *
 * The phone is where these values become the composite stress index: it holds the
 * per-participant baseline and turns each feature into a z-score. The watch only
 * ships features.
 */
data class SensorBundle(
    val bpm: Int,
    val hrv: Int?,
    val spo2: Int?,
    val skinTemp: Double?,
    val baevskySI: Double?,
    val edaTonic: Double?,
    val edaScrPerMin: Int?,
    val motionFlag: Boolean,
    val timestamp: Long
)
