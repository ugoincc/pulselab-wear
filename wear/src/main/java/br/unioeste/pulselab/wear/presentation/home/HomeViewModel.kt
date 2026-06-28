package br.unioeste.pulselab.wear.presentation.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.Wearable
import br.unioeste.pulselab.wear.data.health.HeartRateRepository
import br.unioeste.pulselab.wear.data.health.LiveMeasureMonitor
import br.unioeste.pulselab.wear.data.health.PassiveHeartRateStore
import br.unioeste.pulselab.wear.data.health.PassiveMonitoringManager
import br.unioeste.pulselab.wear.data.health.SamsungSensorMonitor
import br.unioeste.pulselab.wear.data.wearable.WearDataSender
import br.unioeste.pulselab.wear.model.DataSource
import br.unioeste.pulselab.wear.model.HeartRateReading
import br.unioeste.pulselab.wear.model.SensorBundle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "HomeViewModel"

sealed class HomeUiState {
    object Idle : HomeUiState()

    data class Monitoring(
        val bpm: Double?,
        val hrv: Int?,
        val spo2: Int?,
        val stressIndex: Int?,
        val skinTemp: Double?,
        val sessionId: String,
        val readingsSent: Int,
        val source: DataSource,
        val phoneReachable: Boolean? = null,
        val lastUpdate: Long? = null
    ) : HomeUiState()

    data class Error(val message: String) : HomeUiState()
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val heartRateRepo = HeartRateRepository()
    private val liveMonitor = LiveMeasureMonitor(application)
    private val passiveManager = PassiveMonitoringManager(application)
    private val samsungMonitor = SamsungSensorMonitor(application)
    private val wearDataSender = WearDataSender()

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _isPhoneConnected = MutableStateFlow(false)
    val isPhoneConnected: StateFlow<Boolean> = _isPhoneConnected.asStateFlow()

    private val _source = MutableStateFlow(DataSource.LIVE)
    val source: StateFlow<DataSource> = _source.asStateFlow()

    private var monitoringJob: Job? = null

    init {
        Wearable.getNodeClient(application).connectedNodes
            .addOnSuccessListener { nodes -> _isPhoneConnected.value = nodes.isNotEmpty() }

        // Resume a still-registered background session so the user can see/stop it.
        if (passiveManager.isBackgroundActive) {
            _source.value = DataSource.BACKGROUND
            startSession()
        }
    }

    /** Cycle LIVE → BACKGROUND → SIMULATED → LIVE. Live-switches mid-session. */
    fun cycleDataSource() {
        val next = _source.value.next()
        _source.value = next
        val current = _uiState.value as? HomeUiState.Monitoring ?: return
        _uiState.value = current.copy(
            bpm = null, hrv = null, spo2 = null, stressIndex = null, skinTemp = null,
            readingsSent = 0, phoneReachable = null, lastUpdate = null, source = next
        )
    }

    fun startSession() {
        val sessionId = UUID.randomUUID().toString()
        _uiState.value = HomeUiState.Monitoring(
            bpm = null, hrv = null, spo2 = null, stressIndex = null, skinTemp = null,
            sessionId = sessionId, readingsSent = 0, source = _source.value
        )

        // collectLatest cancels the active branch whenever the source changes,
        // so cycling the source hot-swaps without restarting the session.
        monitoringJob = viewModelScope.launch {
            _source.collectLatest { source ->
                when (source) {
                    DataSource.LIVE -> {
                        runCatching { passiveManager.stop() }
                        collectForeground(liveMonitor.heartRateFlow(), DataSource.LIVE)
                    }
                    DataSource.SIMULATED -> {
                        runCatching { passiveManager.stop() }
                        collectForeground(heartRateRepo.simulatedFlow(), DataSource.SIMULATED)
                    }
                    DataSource.SENSOR -> {
                        runCatching { passiveManager.stop() }
                        collectForeground(samsungMonitor.readingFlow(), DataSource.SENSOR)
                    }
                    DataSource.BACKGROUND -> {
                        runCatching { passiveManager.start() }
                            .onFailure { Log.e(TAG, "Failed to start passive monitoring", it) }
                        observePassive()
                    }
                }
            }
        }
    }

    // Foreground sources (LIVE, SIMULATED): the ViewModel displays and sends.
    private suspend fun collectForeground(
        flow: kotlinx.coroutines.flow.Flow<HeartRateReading>,
        source: DataSource
    ) {
        flow.collect { reading ->
            val current = _uiState.value as? HomeUiState.Monitoring ?: return@collect
            _uiState.value = current.copy(
                bpm = reading.bpm,
                hrv = reading.hrv,
                spo2 = reading.spo2,
                stressIndex = reading.stressIndex,
                skinTemp = reading.skinTemp,
                source = source,
                lastUpdate = reading.timestamp
            )
            val bundle = SensorBundle(
                bpm = reading.bpm.toInt(),
                hrv = reading.hrv,
                spo2 = reading.spo2,
                stressIndex = reading.stressIndex,
                skinTemp = reading.skinTemp,
                timestamp = reading.timestamp,
                isSimulated = reading.isSimulated
            )
            val ok = wearDataSender.send(getApplication(), bundle)
            val updated = _uiState.value as? HomeUiState.Monitoring ?: return@collect
            _uiState.value = updated.copy(phoneReachable = ok, readingsSent = updated.readingsSent + 1)
        }
    }

    // Background source: PassiveDataService sends to the phone; here we mirror the
    // shared store into the UI state.
    private suspend fun observePassive() {
        combine(
            PassiveHeartRateStore.latest,
            PassiveHeartRateStore.readingsSent,
            PassiveHeartRateStore.phoneReachable
        ) { reading, sent, reachable -> Triple(reading, sent, reachable) }
            .collect { (reading, sent, reachable) ->
                val current = _uiState.value as? HomeUiState.Monitoring ?: return@collect
                _uiState.value = current.copy(
                    bpm = reading?.bpm,
                    hrv = reading?.hrv,
                    spo2 = reading?.spo2,
                    stressIndex = reading?.stressIndex,
                    skinTemp = reading?.skinTemp,
                    readingsSent = sent,
                    phoneReachable = reachable,
                    source = DataSource.BACKGROUND,
                    lastUpdate = reading?.timestamp
                )
            }
    }

    fun stopSession() {
        monitoringJob?.cancel()
        monitoringJob = null
        // Explicit stop must also end background monitoring.
        viewModelScope.launch { runCatching { passiveManager.stop() } }
        _uiState.value = HomeUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        // Cancel only the in-process collector. We deliberately do NOT stop
        // passive monitoring — that keeps background streaming alive after the
        // app is closed. Only an explicit Stop ends it.
        monitoringJob?.cancel()
    }
}
