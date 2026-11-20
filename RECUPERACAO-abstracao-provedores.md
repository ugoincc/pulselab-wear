# Recuperação: abstração de provedores de sensor (código deletado)

## Correção de premissa antes dos arquivos

O handoff trata `SensorTracker`/`DebugSensorRegistry` e as fontes `LIVE`/`SIMULATED`/`BACKGROUND` como uma coisa só ("a abstração de múltiplos provedores"). São **duas abstrações diferentes, de propósitos distintos, deletadas em commits diferentes**, e isso muda o que dá para reaproveitar:

1. **`SensorTracker`/`DebugSensorRegistry`** (pacote `data/health/debug/`) — abstrai **tipos de tracker dentro do próprio SDK Samsung** (ECG, PPG verde/vermelho/IR, BIA, MF-BIA, EDA, acelerômetro, suor). Todos os 9 são variações de "ler um `HealthTrackerType` do mesmo SDK". Não há Health Services, não há dado simulado, não há noção de "provedor" no sentido de fonte alternativa de hardware/software. Deletado em `e8d87c6c4df6d2a8e7be65275c48dc859db7add4` ("feat: índice de estresse composto — features no relógio (Parte 1)"), 2026-07-27.
2. **`DataSource` (`LIVE`/`BACKGROUND`/`SIMULATED`/`SENSOR`) + `LiveMeasureMonitor`/`PassiveMonitoringManager`/`PassiveDataService`/`PassiveHeartRateStore`/`HeartRateRepository`** — esta sim é uma abstração de **fontes de dado concorrentes e mutuamente exclusivas** (Health Services `MeasureClient`, Health Services `PassiveMonitoringClient`, feed fake, e o próprio SDK Samsung como quarto membro do enum). É a mais próxima do que o TCC precisa para múltiplos provedores + replay. Deletado em `5ed6b6f8a4ae354640922c35c3308d3373afcc3c` ("refactor: single-source relay — Samsung SDK only, auto-start"), 2026-07-23 — **quatro dias antes** da outra remoção.

Ordem cronológica real: `5ed6b6f8` (23/07, remove DataSource/Live/Passive/Repository) → `e8d87c6` (27/07, remove SensorTracker/DebugSensorRegistry). O handoff cita só o segundo commit; o primeiro é onde está o enum de múltiplos provedores.

Os caminhos indicados no handoff também não batiam: `SensorTracker.kt` e `DebugSensorRegistry.kt` viviam em `data/health/debug/`, não direto em `data/health/`. Localizei os caminhos reais via `git log --diff-filter=A` e confirmei a remoção via `git log --diff-filter=D --summary`.

---

## Arquivos recuperados

### `wear/src/main/java/br/unioeste/pulselab/wear/model/DataSource.kt`
(de `5ed6b6f8^`)
```kotlin
package br.unioeste.pulselab.wear.model

/**
 * The mechanism currently feeding readings. Exposed explicitly (instead of a
 * real/fake boolean) so the dev/debug screen can show exactly which Health
 * Services path is active.
 *
 * - [LIVE]: MeasureClient — real heart rate, foreground only, per-second updates.
 * - [BACKGROUND]: PassiveMonitoringClient — real heart rate, batched, survives
 *   backgrounding and process death.
 * - [SIMULATED]: in-app fake feed that fills every metric (for UI testing).
 * - [SENSOR]: Samsung Health Sensor SDK — real HR + IBI (→ HRV/RMSSD), SpO2 and
 *   skin temperature, foreground. Reads sensors directly on the watch.
 */
enum class DataSource(val label: String, val badge: String) {
    LIVE("Live · MeasureClient", "LIVE"),
    BACKGROUND("Background · Passive", "BG"),
    SIMULATED("Fake · Simulated", "SIM"),
    SENSOR("Sensor · Samsung SDK", "SHS");

    fun next(): DataSource = entries[(ordinal + 1) % entries.size]
}
```

### `wear/src/main/java/br/unioeste/pulselab/wear/data/health/HeartRateRepository.kt`
(de `5ed6b6f8^`)
```kotlin
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
                    skinTemp = 31.0 + Math.random() * 3.0,
                    isSimulated = true
                )
            )
            delay(1000L)
        }
    }
}
```

### `wear/src/main/java/br/unioeste/pulselab/wear/data/health/LiveMeasureMonitor.kt`
(de `5ed6b6f8^`)
```kotlin
package br.unioeste.pulselab.wear.data.health

import android.content.Context
import android.util.Log
import androidx.concurrent.futures.await
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import br.unioeste.pulselab.wear.model.HeartRateReading
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "LiveMeasureMonitor"

/**
 * Live, foreground heart rate via Health Services [MeasureClient]. Delivers
 * per-second updates while the app is in the foreground and auto-stops when it
 * is backgrounded (that's by design — use PassiveMonitoringClient for
 * background). MeasureClient only supports HEART_RATE_BPM; HRV/SpO2/skin
 * temp/stress are not available through this API.
 */
class LiveMeasureMonitor(context: Context) {

    private val measureClient =
        HealthServices.getClient(context.applicationContext).measureClient

    suspend fun supportsHeartRate(): Boolean =
        DataType.HEART_RATE_BPM in measureClient.getCapabilitiesAsync().await().supportedDataTypesMeasure

    fun heartRateFlow(): Flow<HeartRateReading> = callbackFlow {
        val callback = object : MeasureCallback {
            override fun onAvailabilityChanged(
                dataType: DeltaDataType<*, *>,
                availability: Availability
            ) {
                if (availability is DataTypeAvailability) {
                    // e.g. ACQUIRING, AVAILABLE, UNAVAILABLE (off-wrist)
                    Log.d(TAG, "HR availability changed: $availability")
                }
            }

            override fun onDataReceived(data: DataPointContainer) {
                val latest = data.getData(DataType.HEART_RATE_BPM)
                    .lastOrNull { it.value > 0 } ?: return
                trySend(
                    HeartRateReading(
                        bpm = latest.value,
                        hrv = null,
                        spo2 = null,
                        stressIndex = null,
                        isSimulated = false
                    )
                )
            }
        }

        measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
        awaitClose {
            measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback)
        }
    }
}
```

### `wear/src/main/java/br/unioeste/pulselab/wear/data/health/PassiveDataService.kt`
(de `5ed6b6f8^`)
```kotlin
package br.unioeste.pulselab.wear.data.health

import android.util.Log
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import br.unioeste.pulselab.wear.data.wearable.WearDataSender
import br.unioeste.pulselab.wear.model.HeartRateReading
import br.unioeste.pulselab.wear.model.SensorBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "PassiveDataService"

/**
 * Receives batched heart-rate data from Health Services and forwards it to the
 * phone — even when the app UI is backgrounded or the process was restarted by
 * the system. This is the component that makes background monitoring a real
 * feature: Health Services binds to it independently of the Activity/ViewModel.
 *
 * Note: passive heart rate is delivered in batches at a lower cadence than a
 * foreground sensor stream, so on-screen updates are less frequent than the
 * simulated/foreground path. Only BPM is available — HRV/SpO2/stress are null.
 */
class PassiveDataService : PassiveListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sender = WearDataSender()

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        // A batch may contain several samples; take the most recent valid one.
        val heartRate = dataPoints.getData(DataType.HEART_RATE_BPM)
            .lastOrNull { it.value > 0 } ?: return

        val reading = HeartRateReading(
            bpm = heartRate.value,
            hrv = null,
            spo2 = null,
            stressIndex = null,
            isSimulated = false
        )
        PassiveHeartRateStore.update(reading)
        Log.d(TAG, "Passive HR received — BPM=${reading.bpm.toInt()}")

        scope.launch {
            val bundle = SensorBundle(
                bpm = reading.bpm.toInt(),
                hrv = null,
                spo2 = null,
                stressIndex = null,
                timestamp = reading.timestamp,
                isSimulated = false
            )
            val ok = sender.send(applicationContext, bundle)
            PassiveHeartRateStore.recordSend(ok)
            Log.d(TAG, "Forwarded passive reading to phone — reachable=$ok")
        }
    }

    override fun onPermissionLost() {
        // Body sensors permission was revoked; passive HR will stop arriving.
        Log.w(TAG, "Body sensors permission lost — passive heart rate stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
```

### `wear/src/main/java/br/unioeste/pulselab/wear/data/health/PassiveHeartRateStore.kt`
(de `5ed6b6f8^`)
```kotlin
package br.unioeste.pulselab.wear.data.health

import br.unioeste.pulselab.wear.model.HeartRateReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide bridge between the background [PassiveDataService] and the UI.
 *
 * Health Services starts [PassiveDataService] inside this app's process, so a
 * singleton holding StateFlows is enough for the ViewModel to observe live
 * readings while the app is running. The service forwards data to the phone
 * regardless of whether any UI is observing — that is what makes background
 * streaming work. These flows are in-memory only and reset when the process
 * is killed; the phone receives the data either way.
 */
object PassiveHeartRateStore {

    private val _latest = MutableStateFlow<HeartRateReading?>(null)
    val latest: StateFlow<HeartRateReading?> = _latest.asStateFlow()

    private val _readingsSent = MutableStateFlow(0)
    val readingsSent: StateFlow<Int> = _readingsSent.asStateFlow()

    private val _phoneReachable = MutableStateFlow<Boolean?>(null)
    val phoneReachable: StateFlow<Boolean?> = _phoneReachable.asStateFlow()

    fun update(reading: HeartRateReading) {
        _latest.value = reading
    }

    /** Called by the service after each send attempt to the phone. */
    fun recordSend(success: Boolean) {
        _phoneReachable.value = success
        if (success) _readingsSent.value += 1
    }

    fun reset() {
        _latest.value = null
        _readingsSent.value = 0
        _phoneReachable.value = null
    }
}
```

### `wear/src/main/java/br/unioeste/pulselab/wear/data/health/PassiveMonitoringManager.kt`
(de `5ed6b6f8^`)
```kotlin
package br.unioeste.pulselab.wear.data.health

import android.content.Context
import android.util.Log
import androidx.concurrent.futures.await
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig

private const val TAG = "PassiveMonitoringManager"

/**
 * Registers and clears background heart-rate monitoring via Health Services'
 * PassiveMonitoringClient.
 *
 * Registration is persisted by Health Services itself — once [start] is called,
 * [PassiveDataService] keeps receiving data across app backgrounding, process
 * death, and reboot, until [stop] is called. We mirror that intent in
 * SharedPreferences so the UI can show (and let the user end) an active
 * background session after the process has been restarted.
 */
class PassiveMonitoringManager(context: Context) {

    private val appContext = context.applicationContext
    private val client = HealthServices.getClient(appContext).passiveMonitoringClient
    private val prefs =
        appContext.getSharedPreferences("passive_monitoring", Context.MODE_PRIVATE)

    /** True if a background session was started and not yet stopped. */
    val isBackgroundActive: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE, false)

    suspend fun start() {
        val config = PassiveListenerConfig.builder()
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .build()
        client.setPassiveListenerServiceAsync(PassiveDataService::class.java, config).await()
        prefs.edit().putBoolean(KEY_ACTIVE, true).apply()
        Log.d(TAG, "Background heart-rate monitoring registered")
    }

    suspend fun stop() {
        client.clearPassiveListenerServiceAsync().await()
        prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
        PassiveHeartRateStore.reset()
        Log.d(TAG, "Background heart-rate monitoring cleared")
    }

    private companion object {
        const val KEY_ACTIVE = "background_active"
    }
}
```

### `wear/src/main/java/br/unioeste/pulselab/wear/model/HeartRateReading.kt`
(bônus — não estava na lista pedida, mas é o tipo de dado que os seis arquivos acima usam; sem ele nenhum compila. De `e8d87c6^`, que é o mesmo em `5ed6b6f8^` já que só foi deletado depois.)
```kotlin
package br.unioeste.pulselab.wear.model

data class HeartRateReading(
    val bpm: Double,
    val hrv: Int?,
    val spo2: Int?,
    val stressIndex: Int?,
    val skinTemp: Double? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isSimulated: Boolean = false
)
```

---

### `wear/src/main/java/br/unioeste/pulselab/wear/data/health/debug/SensorTracker.kt`
(de `e8d87c6^`)
```kotlin
package br.unioeste.pulselab.wear.data.health.debug

import com.samsung.android.service.health.tracking.data.HealthTrackerType
import br.unioeste.pulselab.wear.data.health.SensorConnectionManager
import kotlinx.coroutines.flow.Flow

/**
 * Wraps one Samsung [HealthTrackerType] as an isolated, readable debug metric,
 * so each new sensor can be added as its own class instead of growing one
 * monolithic monitor (mirrors the per-tracker structure in SamsungSensorMonitor.kt,
 * but kept separate from it so that file is never touched by debug additions).
 */
interface SensorTracker<T> {

    /** The Samsung Health Sensor SDK tracker type this class reads from. */
    val trackerType: HealthTrackerType

    /**
     * True for tracker types that stream continuously once started (e.g.
     * HEART_RATE_CONTINUOUS); false for on-demand types that must be explicitly
     * triggered and yield a single measurement per trigger (e.g. SPO2_ON_DEMAND).
     */
    val isContinuous: Boolean

    /** Whether [connection]'s capability report includes [trackerType]. */
    fun isSupported(connection: SensorConnectionManager): Boolean =
        trackerType in connection.supportedTypes()

    /**
     * Starts reading [trackerType] from [connection] and emits typed readings.
     * Continuous trackers emit for as long as the flow is collected; on-demand
     * trackers emit the result of one measurement and then complete.
     */
    fun readingFlow(connection: SensorConnectionManager): Flow<T>
}
```

### `wear/src/main/java/br/unioeste/pulselab/wear/data/health/debug/DebugSensorRegistry.kt`
(de `e8d87c6^`)
```kotlin
package br.unioeste.pulselab.wear.data.health.debug

import android.content.Context
import android.util.Log
import br.unioeste.pulselab.wear.data.health.SensorConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

private const val TAG = "DebugSensorRegistry"

/** Lifecycle of one [DebugMetricState] row. */
enum class DebugMetricStatus { UNAVAILABLE, READY, READING, DONE, ERROR }

/** One row of the debug screen: a metric's display name, status and latest reading. */
data class DebugMetricState(
    val metricId: String,
    val metricName: String,
    val status: DebugMetricStatus,
    val values: List<String>,
    val isOnDemand: Boolean
)

/**
 * Single entry point that owns one [SensorConnectionManager] connection and
 * aggregates all 9 debug metrics (US-002/003/004 trackers) as one observable
 * [uiState], so callers (the future ViewModel) don't need to know about each
 * tracker class individually.
 *
 * One row per PRD metric name (ECG, PPG green/red/ir, BIA, MF-BIA, EDA,
 * accelerometer, sweat loss) — ECG and BIA each have both a continuous and an
 * on-demand SDK type, but this registry surfaces only their on-demand variant
 * so the debug UI has exactly one trigger-driven row per metric, matching the
 * "9 metrics" framing in the PRD description.
 *
 * Connects/disconnects independently of [br.unioeste.pulselab.wear.data.health.SamsungSensorMonitor]
 * and never touches that class, SensorConnectionManager.kt or Hrv.kt.
 */
class DebugSensorRegistry(context: Context) {

    private val appContext = context.applicationContext
    private val connection = SensorConnectionManager(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Metric(
        val id: String,
        val name: String,
        val tracker: SensorTracker<*>,
        val format: (Any?) -> List<String>
    )

    private val metrics: List<Metric> = listOf(
        Metric("ecg", "ECG", EcgTracker(onDemand = true)) { r ->
            val v = r as EcgReading
            listOf("${v.ecgMv ?: "—"} mV", "lead off=${v.leadOff ?: "—"}")
        },
        Metric("ppg_green", "PPG Green", PpgGreenTracker()) { r -> ppgValues(r) },
        Metric("ppg_red", "PPG Red", PpgRedTracker()) { r -> ppgValues(r) },
        Metric("ppg_ir", "PPG IR", PpgIrTracker()) { r -> ppgValues(r) },
        Metric("bia", "BIA", BiaTracker(onDemand = true)) { r ->
            val v = r as BiaReading
            listOf(
                "fat=${v.bodyFatRatio ?: "—"}%",
                "water=${v.totalBodyWater ?: "—"}",
                "BMR=${v.basalMetabolicRate ?: "—"}"
            )
        },
        Metric("mf_bia", "MF-BIA", MfBiaTracker()) { r ->
            val v = r as MfBiaReading
            listOf(
                "5k=${v.impedanceMagnitude5k ?: "—"}",
                "50k=${v.impedanceMagnitude50k ?: "—"}"
            )
        },
        Metric("eda", "EDA", EdaTracker()) { r ->
            val v = r as EdaReading
            listOf("${v.skinConductance ?: "—"} uS")
        },
        Metric("accelerometer", "Accelerometer", AccelerometerTracker()) { r ->
            val v = r as AccelerometerReading
            listOf("x=${v.x ?: "—"} y=${v.y ?: "—"} z=${v.z ?: "—"}")
        },
        Metric("sweat_loss", "Sweat Loss", SweatLossTracker()) { r ->
            val v = r as SweatLossReading
            listOf("${v.sweatLoss ?: "—"} mL")
        }
    )

    private fun ppgValues(reading: Any?): List<String> {
        val v = reading as PpgReading
        return listOf("value=${v.value ?: "—"} status=${v.status ?: "—"}")
    }

    private val _uiState = MutableStateFlow(
        metrics.map {
            DebugMetricState(
                metricId = it.id,
                metricName = it.name,
                status = DebugMetricStatus.UNAVAILABLE,
                values = emptyList(),
                isOnDemand = !it.tracker.isContinuous
            )
        }
    )
    val uiState: StateFlow<List<DebugMetricState>> = _uiState.asStateFlow()

    private val continuousJobs = mutableMapOf<String, Job>()
    private var onDemandJob: Job? = null

    fun connect() {
        connection.onConnected = {
            for (metric in metrics) {
                val supported = metric.tracker.isSupported(connection)
                updateMetric(metric.id) { it.copy(status = if (supported) DebugMetricStatus.READY else DebugMetricStatus.UNAVAILABLE) }
                if (supported && metric.tracker.isContinuous) {
                    startContinuous(metric)
                }
            }
        }
        connection.onError = { e ->
            Log.e(TAG, "Sensor connection error code=${e.errorCode}")
            _uiState.value = _uiState.value.map { it.copy(status = DebugMetricStatus.ERROR) }
        }
        connection.connect()
    }

    fun disconnect() {
        continuousJobs.values.forEach { it.cancel() }
        continuousJobs.clear()
        onDemandJob?.cancel()
        onDemandJob = null
        connection.disconnect()
    }

    /**
     * Starts the on-demand [metricId] tracker's one-shot measurement. Rejected
     * (logged, no-op) if another on-demand tracker is already Reading — on-demand
     * triggers are serialized, never run concurrently.
     */
    fun trigger(metricId: String) {
        val metric = metrics.find { it.id == metricId } ?: return
        if (metric.tracker.isContinuous) return

        val alreadyReading = _uiState.value.any { it.status == DebugMetricStatus.READING }
        if (alreadyReading) {
            Log.w(TAG, "Trigger for $metricId rejected — another on-demand tracker is already Reading")
            return
        }

        updateMetric(metricId) { it.copy(status = DebugMetricStatus.READING) }
        onDemandJob = scope.launch {
            metric.tracker.readingFlow(connection)
                .catch { e ->
                    Log.e(TAG, "$metricId reading failed", e)
                    updateMetric(metricId) { it.copy(status = DebugMetricStatus.ERROR) }
                }
                .collect { reading ->
                    updateMetric(metricId) {
                        it.copy(status = DebugMetricStatus.DONE, values = metric.format(reading))
                    }
                }
        }
    }

    private fun startContinuous(metric: Metric) {
        continuousJobs[metric.id] = scope.launch {
            metric.tracker.readingFlow(connection)
                .catch { e ->
                    Log.e(TAG, "${metric.id} reading failed", e)
                    updateMetric(metric.id) { it.copy(status = DebugMetricStatus.ERROR) }
                }
                .collect { reading ->
                    updateMetric(metric.id) {
                        it.copy(status = DebugMetricStatus.READING, values = metric.format(reading))
                    }
                }
        }
    }

    private fun updateMetric(metricId: String, transform: (DebugMetricState) -> DebugMetricState) {
        _uiState.value = _uiState.value.map { if (it.metricId == metricId) transform(it) else it }
    }
}
```

### `wear/src/main/java/br/unioeste/pulselab/wear/data/health/debug/AccelerometerTracker.kt`
(de `e8d87c6^`)
```kotlin
package br.unioeste.pulselab.wear.data.health.debug

import android.util.Log
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import br.unioeste.pulselab.wear.data.health.SensorConnectionManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "AccelerometerTracker"

/** One accelerometer sample, mapped from [ValueKey.AccelerometerSet] (no STATUS field in this set). */
data class AccelerometerReading(val x: Int?, val y: Int?, val z: Int?, val timestamp: Long)

/**
 * Reads raw accelerometer axes. The SDK exposes both [HealthTrackerType.ACCELEROMETER]
 * and [HealthTrackerType.ACCELEROMETER_CONTINUOUS]; per US-004 both are treated as
 * continuous-type here (no on-demand trigger), defaulting to the CONTINUOUS variant.
 */
class AccelerometerTracker(private val useContinuousType: Boolean = true) :
    SensorTracker<AccelerometerReading> {

    override val trackerType: HealthTrackerType =
        if (useContinuousType) HealthTrackerType.ACCELEROMETER_CONTINUOUS else HealthTrackerType.ACCELEROMETER

    override val isContinuous: Boolean = true

    override fun readingFlow(connection: SensorConnectionManager): Flow<AccelerometerReading> =
        callbackFlow {
            if (!isSupported(connection)) {
                Log.w(TAG, "$trackerType not supported on this device")
                close()
                return@callbackFlow
            }

            val listener = object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: List<DataPoint>) {
                    for (dp in dataPoints) {
                        trySend(
                            AccelerometerReading(
                                x = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X),
                                y = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y),
                                z = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z),
                                timestamp = dp.getTimestamp()
                            )
                        )
                    }
                }

                override fun onFlushCompleted() {}
                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "$trackerType tracker error: $error")
                    close()
                }
            }

            val tracker = connection.tracker(trackerType)?.also { it.setEventListener(listener) }

            awaitClose { tracker?.unsetEventListener() }
        }
}
```

### `wear/src/main/java/br/unioeste/pulselab/wear/data/health/debug/BiaTracker.kt`
(de `e8d87c6^`)
```kotlin
package br.unioeste.pulselab.wear.data.health.debug

import android.util.Log
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import br.unioeste.pulselab.wear.data.health.SensorConnectionManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "BiaTracker"

/** One BIA sample, mapped from [ValueKey.BiaSet]. */
data class BiaReading(
    val bodyFatRatio: Float?,
    val bodyFatMass: Float?,
    val totalBodyWater: Float?,
    val skeletalMuscleRatio: Float?,
    val skeletalMuscleMass: Float?,
    val basalMetabolicRate: Float?,
    val progress: Float?,
    val status: Int?,
    val timestamp: Long
)

/**
 * Reads BIA either continuously ([HealthTrackerType.BIA]) or as a single
 * triggered measurement ([HealthTrackerType.BIA_ON_DEMAND]), depending on
 * [onDemand]. Per US-003, callers should always trigger this on-demand (never
 * auto-start); on-demand mode completes the flow after the first reading.
 */
class BiaTracker(private val onDemand: Boolean = true) : SensorTracker<BiaReading> {

    override val trackerType: HealthTrackerType =
        if (onDemand) HealthTrackerType.BIA_ON_DEMAND else HealthTrackerType.BIA

    override val isContinuous: Boolean = !onDemand

    override fun readingFlow(connection: SensorConnectionManager): Flow<BiaReading> =
        callbackFlow {
            if (!isSupported(connection)) {
                Log.w(TAG, "$trackerType not supported on this device")
                close()
                return@callbackFlow
            }

            val listener = object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: List<DataPoint>) {
                    for (dp in dataPoints) {
                        trySend(
                            BiaReading(
                                bodyFatRatio = dp.getValue(ValueKey.BiaSet.BODY_FAT_RATIO),
                                bodyFatMass = dp.getValue(ValueKey.BiaSet.BODY_FAT_MASS),
                                totalBodyWater = dp.getValue(ValueKey.BiaSet.TOTAL_BODY_WATER),
                                skeletalMuscleRatio = dp.getValue(ValueKey.BiaSet.SKELETAL_MUSCLE_RATIO),
                                skeletalMuscleMass = dp.getValue(ValueKey.BiaSet.SKELETAL_MUSCLE_MASS),
                                basalMetabolicRate = dp.getValue(ValueKey.BiaSet.BASAL_METABOLIC_RATE),
                                progress = dp.getValue(ValueKey.BiaSet.PROGRESS),
                                status = dp.getValue(ValueKey.BiaSet.STATUS),
                                timestamp = dp.getTimestamp()
                            )
                        )
                        if (onDemand) {
                            close()
                            return
                        }
                    }
                }

                override fun onFlushCompleted() {}
                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "$trackerType tracker error: $error")
                    close()
                }
            }

            val tracker = connection.tracker(trackerType)?.also { it.setEventListener(listener) }

            awaitClose { tracker?.unsetEventListener() }
        }
}
```

### `wear/src/main/java/br/unioeste/pulselab/wear/data/health/debug/EcgTracker.kt`
(de `e8d87c6^`)
```kotlin
package br.unioeste.pulselab.wear.data.health.debug

import android.util.Log
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import br.unioeste.pulselab.wear.data.health.SensorConnectionManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "EcgTracker"

/** One ECG sample, mapped from [ValueKey.EcgSet]. */
data class EcgReading(
    val leadOff: Int?,
    val sequence: Int?,
    val ecgMv: Float?,
    val maxThresholdMv: Float?,
    val minThresholdMv: Float?,
    val timestamp: Long
)

/**
 * Reads ECG either continuously ([HealthTrackerType.ECG]) or as a single
 * triggered measurement ([HealthTrackerType.ECG_ON_DEMAND]), depending on
 * [onDemand]. On-demand mode completes the flow after the first reading;
 * continuous mode keeps emitting until the collector cancels.
 */
class EcgTracker(private val onDemand: Boolean = false) : SensorTracker<EcgReading> {

    override val trackerType: HealthTrackerType =
        if (onDemand) HealthTrackerType.ECG_ON_DEMAND else HealthTrackerType.ECG

    override val isContinuous: Boolean = !onDemand

    override fun readingFlow(connection: SensorConnectionManager): Flow<EcgReading> =
        callbackFlow {
            if (!isSupported(connection)) {
                Log.w(TAG, "$trackerType not supported on this device")
                close()
                return@callbackFlow
            }

            val listener = object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: List<DataPoint>) {
                    for (dp in dataPoints) {
                        trySend(
                            EcgReading(
                                leadOff = dp.getValue(ValueKey.EcgSet.LEAD_OFF),
                                sequence = dp.getValue(ValueKey.EcgSet.SEQUENCE),
                                ecgMv = dp.getValue(ValueKey.EcgSet.ECG_MV),
                                maxThresholdMv = dp.getValue(ValueKey.EcgSet.MAX_THRESHOLD_MV),
                                minThresholdMv = dp.getValue(ValueKey.EcgSet.MIN_THRESHOLD_MV),
                                timestamp = dp.getTimestamp()
                            )
                        )
                        if (onDemand) {
                            close()
                            return
                        }
                    }
                }

                override fun onFlushCompleted() {}
                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "$trackerType tracker error: $error")
                    close()
                }
            }

            val tracker = connection.tracker(trackerType)?.also { it.setEventListener(listener) }

            awaitClose { tracker?.unsetEventListener() }
        }
}
```

### `wear/src/main/java/br/unioeste/pulselab/wear/data/health/debug/EdaTracker.kt`
(de `e8d87c6^`)
```kotlin
package br.unioeste.pulselab.wear.data.health.debug

import android.util.Log
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import br.unioeste.pulselab.wear.data.health.SensorConnectionManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "EdaTracker"

/** One EDA (electrodermal activity) sample, mapped from [ValueKey.EdaSet]. */
data class EdaReading(val skinConductance: Float?, val status: Int?, val timestamp: Long)

/**
 * Reads skin conductance from [HealthTrackerType.EDA_CONTINUOUS] — the SDK has no
 * bare EDA or on-demand variant, so this tracker always starts/stops with the
 * caller's flow lifecycle.
 */
class EdaTracker : SensorTracker<EdaReading> {

    override val trackerType: HealthTrackerType = HealthTrackerType.EDA_CONTINUOUS

    override val isContinuous: Boolean = true

    override fun readingFlow(connection: SensorConnectionManager): Flow<EdaReading> =
        callbackFlow {
            if (!isSupported(connection)) {
                Log.w(TAG, "$trackerType not supported on this device")
                close()
                return@callbackFlow
            }

            val listener = object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: List<DataPoint>) {
                    for (dp in dataPoints) {
                        trySend(
                            EdaReading(
                                skinConductance = dp.getValue(ValueKey.EdaSet.SKIN_CONDUCTANCE),
                                status = dp.getValue(ValueKey.EdaSet.STATUS),
                                timestamp = dp.getTimestamp()
                            )
                        )
                    }
                }

                override fun onFlushCompleted() {}
                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "$trackerType tracker error: $error")
                    close()
                }
            }

            val tracker = connection.tracker(trackerType)?.also { it.setEventListener(listener) }

            awaitClose { tracker?.unsetEventListener() }
        }
}
```

### `wear/src/main/java/br/unioeste/pulselab/wear/data/health/debug/MfBiaTracker.kt`
(de `e8d87c6^`)
```kotlin
package br.unioeste.pulselab.wear.data.health.debug

import android.util.Log
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import br.unioeste.pulselab.wear.data.health.SensorConnectionManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "MfBiaTracker"

/** One multi-frequency BIA sample, mapped from [ValueKey.MfBiaSet]. */
data class MfBiaReading(
    val impedanceMagnitude5k: Float?,
    val impedancePhase5k: Float?,
    val impedanceMagnitude10k: Float?,
    val impedancePhase10k: Float?,
    val impedanceMagnitude50k: Float?,
    val impedancePhase50k: Float?,
    val impedanceMagnitude250k: Float?,
    val impedancePhase250k: Float?,
    val progress: Float?,
    val status: Int?,
    val timestamp: Long
)

/**
 * Reads multi-frequency BIA as a single triggered measurement
 * ([HealthTrackerType.MF_BIA_ON_DEMAND]) — the SDK has no continuous variant
 * for this type. Callers should always trigger this explicitly (never
 * auto-start); the flow completes after the first reading.
 */
class MfBiaTracker : SensorTracker<MfBiaReading> {

    override val trackerType: HealthTrackerType = HealthTrackerType.MF_BIA_ON_DEMAND

    override val isContinuous: Boolean = false

    override fun readingFlow(connection: SensorConnectionManager): Flow<MfBiaReading> =
        callbackFlow {
            if (!isSupported(connection)) {
                Log.w(TAG, "$trackerType not supported on this device")
                close()
                return@callbackFlow
            }

            val listener = object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: List<DataPoint>) {
                    for (dp in dataPoints) {
                        trySend(
                            MfBiaReading(
                                impedanceMagnitude5k = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_5K),
                                impedancePhase5k = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_5K),
                                impedanceMagnitude10k = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_10K),
                                impedancePhase10k = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_10K),
                                impedanceMagnitude50k = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_50K),
                                impedancePhase50k = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_50K),
                                impedanceMagnitude250k = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_250K),
                                impedancePhase250k = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_250K),
                                progress = dp.getValue(ValueKey.MfBiaSet.PROGRESS),
                                status = dp.getValue(ValueKey.MfBiaSet.STATUS),
                                timestamp = dp.getTimestamp()
                            )
                        )
                        close()
                        return
                    }
                }

                override fun onFlushCompleted() {}
                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "$trackerType tracker error: $error")
                    close()
                }
            }

            val tracker = connection.tracker(trackerType)?.also { it.setEventListener(listener) }

            awaitClose { tracker?.unsetEventListener() }
        }
}
```

### `wear/src/main/java/br/unioeste/pulselab/wear/data/health/debug/PpgTrackers.kt`
(de `e8d87c6^`)
```kotlin
package br.unioeste.pulselab.wear.data.health.debug

import android.util.Log
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import br.unioeste.pulselab.wear.data.health.SensorConnectionManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** One raw PPG channel sample (green, red or ir — same shape for all three). */
data class PpgReading(val value: Int?, val status: Int?, val timestamp: Long)

/**
 * Shared continuous-tracker plumbing for the three raw PPG channels
 * ([HealthTrackerType.PPG_GREEN]/[HealthTrackerType.PPG_RED]/[HealthTrackerType.PPG_IR]).
 * None of these types has an on-demand variant in the SDK, so all three
 * start/stop with the caller's lifecycle.
 */
private fun ppgReadingFlow(
    tag: String,
    trackerType: HealthTrackerType,
    tracker: SensorTracker<PpgReading>,
    connection: SensorConnectionManager,
    channel: kotlinx.coroutines.channels.ProducerScope<PpgReading>,
    value: (DataPoint) -> Int?,
    status: (DataPoint) -> Int?
) {
    if (!tracker.isSupported(connection)) {
        Log.w(tag, "$trackerType not supported on this device")
        channel.close()
        return
    }

    val listener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                channel.trySend(PpgReading(value(dp), status(dp), dp.getTimestamp()))
            }
        }

        override fun onFlushCompleted() {}
        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(tag, "$trackerType tracker error: $error")
            channel.close()
        }
    }

    val healthTracker = connection.tracker(trackerType)?.also { it.setEventListener(listener) }
    channel.invokeOnClose { healthTracker?.unsetEventListener() }
}

private const val PPG_GREEN_TAG = "PpgGreenTracker"

/** Reads the raw green PPG channel ([ValueKey.PpgGreenSet]). Continuous only. */
class PpgGreenTracker : SensorTracker<PpgReading> {
    override val trackerType: HealthTrackerType = HealthTrackerType.PPG_GREEN
    override val isContinuous: Boolean = true

    override fun readingFlow(connection: SensorConnectionManager): Flow<PpgReading> =
        callbackFlow {
            ppgReadingFlow(
                tag = PPG_GREEN_TAG,
                trackerType = trackerType,
                tracker = this@PpgGreenTracker,
                connection = connection,
                channel = this,
                value = { it.getValue(ValueKey.PpgGreenSet.PPG_GREEN) },
                status = { it.getValue(ValueKey.PpgGreenSet.STATUS) }
            )
            awaitClose {}
        }
}

private const val PPG_RED_TAG = "PpgRedTracker"

/** Reads the raw red PPG channel ([ValueKey.PpgRedSet]). Continuous only. */
class PpgRedTracker : SensorTracker<PpgReading> {
    override val trackerType: HealthTrackerType = HealthTrackerType.PPG_RED
    override val isContinuous: Boolean = true

    override fun readingFlow(connection: SensorConnectionManager): Flow<PpgReading> =
        callbackFlow {
            ppgReadingFlow(
                tag = PPG_RED_TAG,
                trackerType = trackerType,
                tracker = this@PpgRedTracker,
                connection = connection,
                channel = this,
                value = { it.getValue(ValueKey.PpgRedSet.PPG_RED) },
                status = { it.getValue(ValueKey.PpgRedSet.STATUS) }
            )
            awaitClose {}
        }
}

private const val PPG_IR_TAG = "PpgIrTracker"

/** Reads the raw infrared PPG channel ([ValueKey.PpgIrSet]). Continuous only. */
class PpgIrTracker : SensorTracker<PpgReading> {
    override val trackerType: HealthTrackerType = HealthTrackerType.PPG_IR
    override val isContinuous: Boolean = true

    override fun readingFlow(connection: SensorConnectionManager): Flow<PpgReading> =
        callbackFlow {
            ppgReadingFlow(
                tag = PPG_IR_TAG,
                trackerType = trackerType,
                tracker = this@PpgIrTracker,
                connection = connection,
                channel = this,
                value = { it.getValue(ValueKey.PpgIrSet.PPG_IR) },
                status = { it.getValue(ValueKey.PpgIrSet.STATUS) }
            )
            awaitClose {}
        }
}
```

### `wear/src/main/java/br/unioeste/pulselab/wear/data/health/debug/SweatLossTracker.kt`
(de `e8d87c6^`)
```kotlin
package br.unioeste.pulselab.wear.data.health.debug

import android.util.Log
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import br.unioeste.pulselab.wear.data.health.SensorConnectionManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "SweatLossTracker"

/** One sweat loss sample, mapped from [ValueKey.SweatLossSet]. */
data class SweatLossReading(val sweatLoss: Float?, val status: Int?, val timestamp: Long)

/**
 * Reads sweat loss from [HealthTrackerType.SWEAT_LOSS] — the SDK has no continuous
 * or on-demand variant for this type, so it always starts/stops with the caller's
 * flow lifecycle.
 */
class SweatLossTracker : SensorTracker<SweatLossReading> {

    override val trackerType: HealthTrackerType = HealthTrackerType.SWEAT_LOSS

    override val isContinuous: Boolean = true

    override fun readingFlow(connection: SensorConnectionManager): Flow<SweatLossReading> =
        callbackFlow {
            if (!isSupported(connection)) {
                Log.w(TAG, "$trackerType not supported on this device")
                close()
                return@callbackFlow
            }

            val listener = object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: List<DataPoint>) {
                    for (dp in dataPoints) {
                        trySend(
                            SweatLossReading(
                                sweatLoss = dp.getValue(ValueKey.SweatLossSet.SWEAT_LOSS),
                                status = dp.getValue(ValueKey.SweatLossSet.STATUS),
                                timestamp = dp.getTimestamp()
                            )
                        )
                    }
                }

                override fun onFlushCompleted() {}
                override fun onError(error: HealthTracker.TrackerError) {
                    Log.e(TAG, "$trackerType tracker error: $error")
                    close()
                }
            }

            val tracker = connection.tracker(trackerType)?.also { it.setEventListener(listener) }

            awaitClose { tracker?.unsetEventListener() }
        }
}
```

### `wear/src/main/java/br/unioeste/pulselab/wear/presentation/debug/SensorCapabilityViewModel.kt`
(de `e8d87c6^` — não estava na lista pedida, mas é consumidor direto de `DebugSensorRegistry`, incluído para o quadro ficar completo)
```kotlin
package br.unioeste.pulselab.wear.presentation.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import br.unioeste.pulselab.wear.data.health.debug.DebugMetricState
import br.unioeste.pulselab.wear.data.health.debug.DebugSensorRegistry
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin Compose-facing wrapper around [DebugSensorRegistry]: connects on
 * creation, forwards its [uiState], and passes trigger taps straight through
 * — the registry already owns all status/serialization bookkeeping.
 */
class SensorCapabilityViewModel(application: Application) : AndroidViewModel(application) {

    private val registry = DebugSensorRegistry(application)

    val uiState: StateFlow<List<DebugMetricState>> = registry.uiState

    init {
        registry.connect()
    }

    fun onTrigger(metricId: String) {
        registry.trigger(metricId)
    }

    override fun onCleared() {
        super.onCleared()
        registry.disconnect()
    }
}
```

### `wear/src/main/java/br/unioeste/pulselab/wear/presentation/debug/SensorCapabilityScreen.kt`
(de `e8d87c6^` — não estava na lista pedida, incluído pelo mesmo motivo)
```kotlin
package br.unioeste.pulselab.wear.presentation.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import br.unioeste.pulselab.wear.data.health.debug.DebugMetricState
import br.unioeste.pulselab.wear.data.health.debug.DebugMetricStatus

// SensorCapabilityScreen is split the same way as HomeScreen:
//   SensorCapabilityScreen        — knows about the ViewModel (stateful, not previewable)
//   SensorCapabilityScreenContent — only knows about data + callbacks (stateless, previewable)

@Composable
fun SensorCapabilityScreen(viewModel: SensorCapabilityViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    SensorCapabilityScreenContent(
        metrics = uiState,
        onTrigger = { metricId -> viewModel.onTrigger(metricId) }
    )
}

private fun DebugMetricStatus.label(): String = when (this) {
    DebugMetricStatus.UNAVAILABLE -> "Unavailable"
    DebugMetricStatus.READY -> "Ready"
    DebugMetricStatus.READING -> "Reading..."
    DebugMetricStatus.DONE -> "Done"
    DebugMetricStatus.ERROR -> "Error"
}

private fun DebugMetricStatus.badgeColor(): Color = when (this) {
    DebugMetricStatus.UNAVAILABLE -> Color.Gray
    DebugMetricStatus.READY -> Color(0xFF9E9E9E)
    DebugMetricStatus.READING -> Color(0xFFFFC107)
    DebugMetricStatus.DONE -> Color(0xFF4CAF50)
    DebugMetricStatus.ERROR -> Color(0xFFF44336)
}

// ─── STATELESS CONTENT ──────────────────────────────────────────────────────
@Composable
fun SensorCapabilityScreenContent(
    metrics: List<DebugMetricState>,
    onTrigger: (String) -> Unit = {}
) {
    AppScaffold {
        val listState = rememberTransformingLazyColumnState()

        ScreenScaffold(scrollState = listState) { contentPadding ->
            TransformingLazyColumn(contentPadding = contentPadding, state = listState) {

                item {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text("Sensor Capabilities")
                    }
                }

                items(metrics) { metric ->
                    MetricRow(metric = metric, onTrigger = onTrigger)
                }
            }
        }
    }
}

@Composable
private fun MetricRow(metric: DebugMetricState, onTrigger: (String) -> Unit) {
    val isUnavailable = metric.status == DebugMetricStatus.UNAVAILABLE
    val contentAlpha = if (isUnavailable) 0.4f else 1f

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = metric.metricName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            )
            Text(
                text = "  ${metric.status.label()}",
                style = MaterialTheme.typography.labelSmall,
                color = metric.status.badgeColor().copy(alpha = contentAlpha)
            )
        }
        Text(
            text = if (metric.values.isEmpty()) "—" else metric.values.joinToString(" "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
        )
        if (!isUnavailable && metric.isOnDemand) {
            Button(
                onClick = { onTrigger(metric.metricId) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (metric.status == DebugMetricStatus.READING) "Reading..." else "Trigger")
            }
        }
    }
}

// ─── PREVIEWS ───────────────────────────────────────────────────────────────

private fun previewMetric(
    status: DebugMetricStatus,
    isOnDemand: Boolean = true,
    values: List<String> = emptyList()
) = DebugMetricState(
    metricId = "ecg",
    metricName = "ECG",
    status = status,
    values = values,
    isOnDemand = isOnDemand
)

@WearPreviewDevices
@Composable
fun PreviewUnavailable() {
    SensorCapabilityScreenContent(metrics = listOf(previewMetric(DebugMetricStatus.UNAVAILABLE)))
}

@WearPreviewDevices
@Composable
fun PreviewReady() {
    SensorCapabilityScreenContent(metrics = listOf(previewMetric(DebugMetricStatus.READY)))
}

@WearPreviewDevices
@Composable
fun PreviewReading() {
    SensorCapabilityScreenContent(metrics = listOf(previewMetric(DebugMetricStatus.READING)))
}

@WearPreviewDevices
@Composable
fun PreviewDone() {
    SensorCapabilityScreenContent(
        metrics = listOf(
            previewMetric(DebugMetricStatus.DONE, values = listOf("0.4 mV", "lead off=0"))
        )
    )
}

@WearPreviewDevices
@Composable
fun PreviewError() {
    SensorCapabilityScreenContent(metrics = listOf(previewMetric(DebugMetricStatus.ERROR)))
}
```

---

## 3. Respostas às três perguntas

### Compilava sozinho, ou dependia de algo que só existia antes do refactor?

**Grupo `DataSource`/`Live`/`Passive`/`Repository` (6 arquivos, de `5ed6b6f8^`):**
- Todos dependem de `br.unioeste.pulselab.wear.model.HeartRateReading` — que **ainda existia** nesse ponto do histórico (só foi deletado 4 dias depois, em `e8d87c6`). Hoje ele não existe mais na árvore; foi substituído por `SensorReading` (`wear/src/main/java/br/unioeste/pulselab/wear/model/SensorReading.kt`), com forma diferente (sem `stressIndex`, sem `isSimulated`; com `baevskySI`, `edaTonic`, `edaScrPerMin`, `motionFlag`).
- `LiveMeasureMonitor.kt`, `PassiveDataService.kt`, `PassiveMonitoringManager.kt` dependem de `androidx.health.services.client.*` (Health Services). Essa dependência Gradle **ainda está declarada** hoje em `wear/build.gradle.kts:93` (`implementation(libs.androidx.health.services.client)`), mesmo sem nenhum arquivo fonte que a use atualmente — não precisaria adicionar a lib de volta.
- `PassiveDataService.kt` chama `WearDataSender().send(applicationContext, bundle)` passando um **`SensorBundle` único**, com o construtor antigo (`bpm, hrv, spo2, stressIndex, timestamp, isSimulated`). O `SensorBundle` atual tem campos diferentes (`skinTemp`, `baevskySI`, `edaTonic`, `edaScrPerMin`, `motionFlag`, sem `stressIndex`/`isSimulated`) e o `WearDataSender.send()` atual espera um `SensorBatch` (lista), não um bundle único (`WearDataSender.kt:15`). **Não compila contra o código atual sem adaptação** — nem a forma do dado nem a assinatura do método batem mais.
- Resumindo: nenhum destes 6 arquivos compila hoje "de graça". Todos dependiam de um tipo (`HeartRateReading`) que foi removido depois, e dois deles (`PassiveDataService.kt`, indiretamente `WearDataSender`) dependiam de uma forma de payload que também mudou.

**Grupo `SensorTracker`/`DebugSensorRegistry`/trackers (9 arquivos + 2 de UI, de `e8d87c6^`):**
- Dependem de `br.unioeste.pulselab.wear.data.health.SensorConnectionManager` — esse arquivo **ainda existe hoje**, inalterado o suficiente para o que é usado aqui: `tracker(type)` (`SensorConnectionManager.kt:71`) e `supportedTypes()` (`:65`) continuam com a mesma assinatura.
- Não dependem de `HeartRateReading`, `SensorBundle`, nem de nada do fluxo de streaming — são isolados, exatamente como o comentário do próprio `SensorTracker.kt` promete ("kept separate from it so that file is never touched by debug additions").
- **Este grupo compilaria hoje quase sem alteração** — é o único dos dois que não colidiu com mudanças posteriores no formato do payload.

### Como a fonte `SIMULATED` gerava sinal?

Hardcoded, dentro de `HeartRateRepository.simulatedFlow()` (arquivo acima). Não há arquivo de dados fixo nem gerador paramétrico:
- `bpm`: passeio aleatório (random walk) começando em 72.0, `bpm += (Math.random() - 0.5) * 2`, restrito a `[65.0, 80.0]` via `coerceIn`.
- `hrv`: inteiro aleatório uniforme em `40..60`, sem relação com o `bpm` da mesma emissão.
- `spo2`: inteiro aleatório uniforme em `97..99`.
- `stressIndex`: inteiro aleatório uniforme em `20..40`.
- `skinTemp`: `31.0 + Math.random() * 3.0`.
- Emitido a cada 1000 ms (`delay(1000L)`), em loop infinito, sem seed fixa — cada execução gera uma série diferente.

Nenhum campo depende de nenhum outro (não há acoplamento fisiológico entre bpm/hrv/spo2 simulados), não há descontinuidades/artefatos propositais, não há EDA/motionFlag simulados (esses campos não existiam ainda nessa versão do modelo). **Não é reaproveitável como "sinal de verdade conhecida" para medir latência/perda** — é ruído independente por campo, sem timestamp de origem distinto de `System.currentTimeMillis()` (default do `HeartRateReading`), e sem nenhum mecanismo de replay de uma série gravada. Um provedor de replay para o TCC precisaria ser escrito do zero; nada aqui é reaproveitável para essa finalidade além do padrão geral "implementa uma interface e emite um `Flow` a 1 Hz".

### `DebugSensorRegistry` — "9 trackers" de quê exatamente?

Lista exata, na ordem declarada em `DebugSensorRegistry.kt` (bloco `metrics`):

1. `ecg` → `EcgTracker(onDemand = true)` → `HealthTrackerType.ECG_ON_DEMAND`
2. `ppg_green` → `PpgGreenTracker()` → `HealthTrackerType.PPG_GREEN`
3. `ppg_red` → `PpgRedTracker()` → `HealthTrackerType.PPG_RED`
4. `ppg_ir` → `PpgIrTracker()` → `HealthTrackerType.PPG_IR`
5. `bia` → `BiaTracker(onDemand = true)` → `HealthTrackerType.BIA_ON_DEMAND`
6. `mf_bia` → `MfBiaTracker()` → `HealthTrackerType.MF_BIA_ON_DEMAND`
7. `eda` → `EdaTracker()` → `HealthTrackerType.EDA_CONTINUOUS`
8. `accelerometer` → `AccelerometerTracker()` → `HealthTrackerType.ACCELEROMETER_CONTINUOUS`
9. `sweat_loss` → `SweatLossTracker()` → `HealthTrackerType.SWEAT_LOSS`

É **um tracker por tipo de sensor do SDK Samsung que não faz parte do pipeline principal** (o pipeline principal — `SamsungSensorMonitor` — cobre `HEART_RATE_CONTINUOUS`, `SPO2_ON_DEMAND`, `SKIN_TEMPERATURE_CONTINUOUS`, `ACCELEROMETER_CONTINUOUS`, e hoje também tentaria `EDA_CONTINUOUS`; nenhum desses está na lista de 9 do registry, exceto o acelerômetro, que aparece nos dois — no registry como leitura bruta de eixos X/Y/Z, no pipeline principal só como insumo do `MotionGate`). O comentário do próprio arquivo (`DebugSensorRegistry.kt:212-216`) confirma a intenção: "um row por nome de métrica do PRD... matching the '9 metrics' framing in the PRD description" — ou seja, a divisão em 9 vem do PRD original (`tasks/prd-sensor-capability-debug-screen.md`, ainda presente no repositório, não lido nesta auditoria), não de uma escolha arquitetural sobre "provedores".

**Não há, neste registry, nenhuma noção de fonte alternativa (Health Services, simulação, replay)** — todos os 9 leem exclusivamente do mesmo `SensorConnectionManager`/SDK Samsung. É uma abstração de "quais tipos de tracker deste SDK", não de "quais provedores de dado".
