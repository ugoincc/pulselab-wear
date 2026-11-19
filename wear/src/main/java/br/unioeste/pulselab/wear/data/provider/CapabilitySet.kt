package br.unioeste.pulselab.wear.data.provider

/**
 * Declares what a [SensorProvider] is capable of supplying, once per
 * connection — not re-evaluated per sample. A provider may declare support
 * for a field even though a specific sample comes back null (e.g. spo2
 * momentarily unavailable does not change the provider's declared capability).
 *
 * ESPECIFICACAO-protocolo-payload-v1.md §6.3.
 */
data class CapabilitySet(
    val providesHrv: Boolean,
    val providesBaevskySI: Boolean,
    val providesSpo2: Boolean,
    val providesSkinTemp: Boolean,
    val providesEda: Boolean,
    val providesMotionFlag: Boolean
)

/**
 * Wire envelope for [MessagePaths.SENSOR_CAPABILITIES]
 * (ESPECIFICACAO-protocolo-payload-v1.md §4/§6.3): identifies which provider
 * the declaration belongs to, since capabilities are sent once per connection,
 * separate from the per-sample [br.unioeste.pulselab.wear.model.SensorBatch] stream.
 */
data class CapabilityAnnouncement(
    val providerId: String,
    val capabilities: CapabilitySet
)
