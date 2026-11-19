package br.unioeste.pulselab.wear.data.provider

import android.content.Context
import br.unioeste.pulselab.wear.data.health.SAMSUNG_PROVIDER_ID
import br.unioeste.pulselab.wear.data.health.SamsungSensorMonitor
import br.unioeste.pulselab.wear.model.SensorBundle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [SensorProvider] wrapping [SamsungSensorMonitor] as-is (ESPECIFICACAO-protocolo-payload-v1.md
 * §6.4) — no changes to `SamsungSensorMonitor` or `SensorConnectionManager`.
 *
 * The default source: [br.unioeste.pulselab.wear.data.health.SensorStreamService]
 * collects through this when [br.unioeste.pulselab.wear.data.provider.ProviderChoice.SAMSUNG]
 * is selected. Its capability set is the maximal one of the three providers,
 * which is what makes the asymmetry against [HealthServicesProvider] visible
 * to the phone without either side special-casing the other.
 */
class SamsungSensorProvider(context: Context) : SensorProvider {

    private val monitor = SamsungSensorMonitor(context)

    override val providerId: String = SAMSUNG_PROVIDER_ID

    /**
     * Everything except EDA: `EDA_CONTINUOUS` is absent from the Galaxy Watch
     * Ultra's capability list (SENSORS.md §2, "Não disponíveis"), so
     * `SamsungSensorMonitor` never populates `edaTonic`/`edaScrPerMin` on this
     * hardware. `providesEda = false` reflects that, not a limitation of the
     * SDK itself.
     */
    override suspend fun capabilities(): CapabilitySet = CapabilitySet(
        providesHrv = true,
        providesBaevskySI = true,
        providesSpo2 = true,
        providesSkinTemp = true,
        providesEda = false,
        providesMotionFlag = true
    )

    override fun readingFlow(): Flow<List<SensorBundle>> =
        monitor.readingFlow().map { batch ->
            batch.map { reading ->
                SensorBundle(
                    bpm = reading.bpm.toInt(),
                    hrv = reading.hrv,
                    spo2 = reading.spo2,
                    skinTemp = reading.skinTemp,
                    baevskySI = reading.baevskySI,
                    edaTonic = reading.edaTonic,
                    edaScrPerMin = reading.edaScrPerMin,
                    motionFlag = reading.motionFlag,
                    timestamp = reading.timestamp,
                    schemaVersion = reading.schemaVersion,
                    sequenceNumber = reading.sequenceNumber,
                    spo2MeasuredAt = reading.spo2MeasuredAt,
                    skinTempMeasuredAt = reading.skinTempMeasuredAt,
                    providerId = reading.providerId
                )
            }
        }

    override fun disconnect() {
        // SamsungSensorMonitor.readingFlow() releases its connection in
        // awaitClose when the Flow's collection is cancelled — there is no
        // separate disconnect handle to call here. Present to satisfy the
        // SensorProvider contract for callers that manage providers uniformly.
    }
}
