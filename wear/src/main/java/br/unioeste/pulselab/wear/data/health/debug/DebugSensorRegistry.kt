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
