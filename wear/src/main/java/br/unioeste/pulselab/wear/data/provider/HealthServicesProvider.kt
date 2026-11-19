package br.unioeste.pulselab.wear.data.provider

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import androidx.concurrent.futures.await
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.HeartRateAccuracy
import br.unioeste.pulselab.wear.data.health.MotionGate
import br.unioeste.pulselab.wear.model.SensorBundle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Instant

private const val TAG = "HealthServicesProvider"

/**
 * [SensorProvider.providerId] for the Jetpack Health Services source
 * (ESPECIFICACAO-protocolo-payload-v1.md §4).
 */
const val HEALTH_SERVICES_PROVIDER_ID = "health-services"

/**
 * Peak-to-peak threshold for [MotionGate] when it is fed by the *Android*
 * accelerometer instead of the Samsung SDK's, in the milli-m/s² units
 * [accelerometerListener] scales to.
 *
 * `MotionGate.DEFAULT_THRESHOLD` (200) cannot be reused: it was measured in the
 * Samsung SDK's raw integer counts, and no documented conversion to m/s² exists
 * — mapping one onto the other would be inventing a calibration.
 *
 * 1000 milli-m/s² ≈ 0.1 g of peak-to-peak spread in the acceleration magnitude.
 * At rest the magnitude sits at a constant ~9.81 m/s² whatever the wrist's
 * orientation, so the resting spread is dominated by sensor noise and
 * breathing, well under this; deliberate handling exceeds it by an order of
 * magnitude. **This is a physical argument, not a measurement.** It has to be
 * re-derived from device data the same way `MotionGate.DEFAULT_THRESHOLD` was
 * (`peakToPeak()` is logged for exactly that), and until then the motion flag
 * from this provider is provisional.
 */
private const val ANDROID_ACCEL_THRESHOLD_MILLI_MS2 = 1_000.0

/**
 * The second provider of Objetivo 4, and the reason the abstraction has
 * anything to demonstrate: it reads the same wrist through a different API and
 * comes back with a strictly smaller set of channels.
 *
 * Adapted from `LiveMeasureMonitor` (removed in `d7f6a593`, recovered from
 * history). What changed since: the payload is now [SensorBundle] with the v1
 * fields — schema version, sequence number, provider id, per-channel measured
 * instants — and the flow emits *batches*, since the callback is the platform's
 * delivery boundary (SENSORS.md §4.1) and the transport sends one message per
 * batch.
 *
 * **What this provider structurally cannot supply.** `MeasureClient` exposes
 * `HEART_RATE_BPM` and nothing else of interest here — in particular it does
 * not expose inter-beat intervals. RMSSD and the Baevsky Stress Index are both
 * computed from IBI (`IbiWindow`, `BaevskyStressIndex`), so there is no
 * derivation that recovers them from this source; SpO2, skin temperature and
 * EDA are simply not offered. Those fields go out `null`.
 *
 * The point of [capabilities] is that `null` alone would not say this. A `null`
 * `hrv` from `SamsungSensorProvider` means "the IBI window has not filled yet,
 * ask again in a minute"; a `null` `hrv` from here means "never, from this
 * source". Same wire value, different meaning — and it is the declared
 * capability, sent once per connection, that tells the phone which one it is
 * (ESPECIFICACAO §6.3).
 *
 * **Foreground requirement.** `MeasureClient` delivers only while the app holds
 * foreground standing and stops on its own when backgrounded — by design, with
 * `PassiveMonitoringClient` as the background path. That is survivable here
 * only because collection happens inside
 * `SensorStreamService`, a `foregroundServiceType="health"` service. It is also
 * a difference in kind from the Samsung provider, which keeps delivering
 * (in bursts) while dozing, and it is a confound for any comparison of the two
 * providers under the screen-off conditions of cap6-avaliacao.tex §6.5.
 *
 * @param readAccelerometer whether to read the Android accelerometer separately
 *   to populate `motionFlag`. Health Services does not carry motion alongside
 *   heart rate, so this is the only way this provider can honestly declare
 *   [CapabilitySet.providesMotionFlag]; setting it false makes the provider
 *   heart-rate-only, which is also a legitimate configuration
 *   (ESPECIFICACAO §6.4).
 */
class HealthServicesProvider(
    context: Context,
    private val readAccelerometer: Boolean = true
) : SensorProvider {

    private val appContext = context.applicationContext
    private val measureClient = HealthServices.getClient(appContext).measureClient
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    override val providerId: String = HEALTH_SERVICES_PROVIDER_ID

    /**
     * Everything false except, conditionally, the motion flag.
     *
     * `bpm` has no entry because it has no entry to have: it is non-nullable in
     * the payload (ESPECIFICACAO §3), so every provider supplies it by
     * definition and [CapabilitySet] carries no `providesBpm`. If this device
     * turns out not to support `HEART_RATE_BPM` at all, that is not something
     * the capability contract can express — it is logged here and the flow will
     * simply never emit.
     */
    override suspend fun capabilities(): CapabilitySet {
        if (!supportsHeartRate()) {
            Log.w(TAG, "HEART_RATE_BPM absent from MeasureClient capabilities on this device")
        }
        return CapabilitySet(
            providesHrv = false,
            providesBaevskySI = false,
            providesSpo2 = false,
            providesSkinTemp = false,
            providesEda = false,
            providesMotionFlag = readAccelerometer && accelerometer() != null
        )
    }

    private suspend fun supportsHeartRate(): Boolean = runCatching {
        DataType.HEART_RATE_BPM in
            measureClient.getCapabilitiesAsync().await().supportedDataTypesMeasure
    }.getOrElse {
        Log.e(TAG, "Could not read MeasureClient capabilities", it)
        false
    }

    private fun accelerometer(): Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /**
     * One batch per `MeasureCallback` delivery, mirroring `SamsungSensorMonitor`:
     * the callback is where the platform draws the boundary, and preserving it
     * lets the transport send one Data Layer message per burst.
     *
     * `sequenceNumber` restarts at 0 on each collection, per ESPECIFICACAO §5 —
     * collection is this provider's connection lifecycle, the same scope the
     * Samsung provider uses.
     */
    override fun readingFlow(): Flow<List<SensorBundle>> = callbackFlow {
        // MotionGate is documented as not thread-safe, and here — unlike in
        // SamsungSensorMonitor, where every tracker shares one SDK callback
        // thread — it is written from the SensorManager thread and read from
        // the Health Services callback thread. Guarded rather than made
        // thread-safe in place: MotionGate is used by the Samsung path on a
        // single thread, and adding locking there would be paying for a hazard
        // that does not exist.
        val motionGate = SynchronizedMotionGate(
            MotionGate(threshold = ANDROID_ACCEL_THRESHOLD_MILLI_MS2)
        )
        var sequenceNumber = 0L

        // Health Services stamps data points as a duration since boot; the
        // payload's `timestamp` is epoch ms (ESPECIFICACAO §3), so the boot
        // instant is what bridges them. Captured once, outside the callback:
        // recomputing it per sample would fold the jitter of two clock reads
        // into every acquisition timestamp, which is precisely the quantity the
        // latency metric is trying to observe.
        val bootInstant = Instant.ofEpochMilli(
            System.currentTimeMillis() - SystemClock.elapsedRealtime()
        )

        val measureCallback = object : MeasureCallback {
            override fun onAvailabilityChanged(
                dataType: DeltaDataType<*, *>,
                availability: Availability
            ) {
                if (availability is DataTypeAvailability) {
                    // ACQUIRING / AVAILABLE / UNAVAILABLE (off-wrist). Logged
                    // rather than acted on: a gap in delivery is data about the
                    // source, and suppressing or synthesising samples around it
                    // would corrupt the very series being measured.
                    Log.d(TAG, "HR availability changed: $availability")
                }
            }

            override fun onDataReceived(data: DataPointContainer) {
                val points = data.getData(DataType.HEART_RATE_BPM)
                if (points.isEmpty()) return

                val batch = ArrayList<SensorBundle>(points.size)
                for (point in points) {
                    val bpm = point.value
                    if (bpm <= 0.0) continue

                    val status = (point.accuracy as? HeartRateAccuracy)?.sensorStatus
                    if (status == HeartRateAccuracy.SensorStatus.NO_CONTACT ||
                        status == HeartRateAccuracy.SensorStatus.UNRELIABLE
                    ) {
                        continue
                    }

                    batch += SensorBundle(
                        // Truncated, not rounded — the same conversion the
                        // Samsung path applies (ESPECIFICACAO §3, `bpm`), kept
                        // identical so a difference between providers is never
                        // an artefact of rounding.
                        bpm = bpm.toInt(),
                        // Null because the source has no IBI, not because this
                        // sample happened to be short of one — that distinction
                        // is what capabilities() carries.
                        hrv = null,
                        spo2 = null,
                        skinTemp = null,
                        baevskySI = null,
                        edaTonic = null,
                        edaScrPerMin = null,
                        motionFlag = motionGate.isMoving(),
                        timestamp = point.getTimeInstant(bootInstant).toEpochMilli(),
                        sequenceNumber = sequenceNumber++,
                        // Null iff the value is null (ESPECIFICACAO §4), and
                        // both values are always null here.
                        spo2MeasuredAt = null,
                        skinTempMeasuredAt = null,
                        providerId = HEALTH_SERVICES_PROVIDER_ID
                    )
                }

                if (batch.isNotEmpty()) trySend(batch)
            }
        }

        val accelListener = if (readAccelerometer) accelerometerListener(motionGate) else null
        accelListener?.let { listener ->
            val sensor = accelerometer()
            if (sensor == null) {
                Log.w(TAG, "No accelerometer on this device — motionFlag stays false")
            } else {
                sensorManager?.registerListener(
                    listener, sensor, SensorManager.SENSOR_DELAY_NORMAL
                )
            }
        }

        measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, measureCallback)

        awaitClose {
            accelListener?.let { sensorManager?.unregisterListener(it) }
            measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, measureCallback)
            Log.d(TAG, "Health Services provider closed — callbacks unregistered")
        }
    }

    /**
     * Feeds [MotionGate] from the Android accelerometer.
     *
     * Two conversions, both forced by [MotionGate] having been written against
     * the Samsung SDK: axes arrive as m/s² floats and the gate takes integers,
     * so they are scaled to milli-m/s² (truncating to whole m/s² would quantise
     * away the entire signal — resting spread is a fraction of one unit); and
     * `SensorEvent.timestamp` is nanoseconds since boot, not epoch ms, so it is
     * converted to the gate's millisecond window.
     */
    private fun accelerometerListener(gate: SynchronizedMotionGate) = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.size < 3) return
            gate.add(
                x = (event.values[0] * 1000).toInt(),
                y = (event.values[1] * 1000).toInt(),
                z = (event.values[2] * 1000).toInt(),
                atMs = event.timestamp / 1_000_000
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * Nothing to release outside a collection: both callbacks are unregistered
     * in the flow's `awaitClose`. Present for callers that manage providers
     * uniformly, and idempotent by being empty.
     */
    override fun disconnect() = Unit
}

/**
 * Mutual exclusion around a [MotionGate] whose writer and reader live on
 * different threads. Only the two operations this provider needs are exposed.
 */
private class SynchronizedMotionGate(private val gate: MotionGate) {
    private val lock = Any()

    fun add(x: Int, y: Int, z: Int, atMs: Long) = synchronized(lock) {
        gate.add(x, y, z, atMs)
    }

    fun isMoving(): Boolean = synchronized(lock) { gate.isMoving() }
}
