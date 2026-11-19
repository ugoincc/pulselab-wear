package br.unioeste.pulselab.wear.data.provider

import br.unioeste.pulselab.wear.model.SensorBundle
import kotlinx.coroutines.flow.Flow

/**
 * An alternative source of biometric data, behind the same payload contract.
 * Each concrete implementation (Samsung SDK, Health Services, replay) stays
 * isolated — none touches another's internal dependencies.
 *
 * ESPECIFICACAO-protocolo-payload-v1.md §6.1/§6.2. `SensorConnectionManager`
 * is not wrapped generically here: its public surface already speaks Samsung
 * SDK vocabulary end to end (`HealthTrackerType`, `HealthTracker`,
 * `HealthTrackerException`), so generalising over it would mean breaking that
 * coupling. Instead [SamsungSensorProvider] uses `SensorConnectionManager`
 * internally, exactly as `SamsungSensorMonitor` already does, unchanged.
 *
 * The spec's §6.2 signature names the emitted element type `SensorPayloadV1`.
 * There is no separate type by that name in this codebase — it is
 * [SensorBundle] with the v1 fields (see `SENSOR_SCHEMA_VERSION`,
 * `SensorBundle.schemaVersion`), so this interface names it directly.
 */
interface SensorProvider {

    /** Stable identifier, goes in the payload's `providerId` field. */
    val providerId: String

    /**
     * Capabilities declared once, on connect — not re-evaluated per sample.
     */
    suspend fun capabilities(): CapabilitySet

    /**
     * Starts reading and emits batches of samples already in payload format
     * (SensorBundle, ESPECIFICACAO-protocolo-payload-v1.md §3+4). Continuous
     * implementations emit for as long as the Flow is collected; the batch
     * boundary is decided by each provider according to the granularity its
     * data source actually delivers — this interface imposes no fixed batch
     * size.
     */
    fun readingFlow(): Flow<List<SensorBundle>>

    /** Releases connection resources. Idempotent. */
    fun disconnect()
}
