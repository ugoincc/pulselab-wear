package br.unioeste.pulselab.wear.model

object MessagePaths {
    /**
     * One message per sensor burst, carrying a [SensorBatch].
     *
     * A new path rather than a new shape on the old `/sensor/data`: a phone build
     * that predates batching listens only to the old path, so it now receives
     * nothing instead of parsing an array as if it were one sample. Failing
     * silent beats failing wrong.
     */
    const val SENSOR_BATCH = "/sensor/batch"

    /**
     * One message per provider connection, carrying a `CapabilityAnnouncement`
     * (ESPECIFICACAO-protocolo-payload-v1.md §6.3) — sent once, not per sample,
     * separate from [SENSOR_BATCH] so the phone doesn't have to unpack a
     * capability declaration out of every batch.
     *
     * Sent by [br.unioeste.pulselab.wear.data.health.SensorStreamService] when it starts
     * collecting a provider's flow, and re-attempted on the first batch that
     * lands if the phone was unreachable at that moment — a run whose
     * announcement was dropped would leave the phone unable to tell "null
     * because not yet" from "null because never".
     */
    const val SENSOR_CAPABILITIES = "/sensor/capabilities"
}
