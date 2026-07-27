package br.unioeste.pulselab.wear.model

/**
 * One sample inside a [SensorBatch]. Field names are the contract with the phone
 * app's WearBridge — renaming one is a cross-repo breaking change.
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

/**
 * Wire format sent to the phone on [MessagePaths.SENSOR_BATCH], serialised as
 * JSON by Gson.
 *
 * One message per sensor burst, deliberately. With the screen dozing the platform
 * withholds delivery and hands us bursts of up to 66 samples at once (measured
 * 2026-07-27, see SENSORS.md §4.1) — one Data Layer message per sample meant ~66
 * messages inside a second, which is exactly the traffic pattern Bluetooth
 * throttling punishes.
 *
 * The cadence is the platform's, not ours: we send what arrived, when it arrived.
 * Imposing our own fixed interval on top would only add latency to a delay we do
 * not control.
 *
 * [samples] is ordered oldest first and each carries its own true acquisition
 * timestamp, so a delayed batch is still a faithful 1 Hz series.
 */
data class SensorBatch(
    val samples: List<SensorBundle>
)
