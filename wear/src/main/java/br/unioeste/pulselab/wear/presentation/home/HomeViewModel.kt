package br.unioeste.pulselab.wear.presentation.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.Wearable
import br.unioeste.pulselab.wear.data.health.HeartRateRepository
import br.unioeste.pulselab.wear.data.health.PassiveHeartRateStore
import br.unioeste.pulselab.wear.data.health.PassiveMonitoringManager
import br.unioeste.pulselab.wear.data.wearable.WearDataSender
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
        val sessionId: String,
        val readingsSent: Int,
        val phoneReachable: Boolean? = null,
        val isSimulated: Boolean = false,
        val isBackground: Boolean = false
    ) : HomeUiState()

    data class Error(val message: String) : HomeUiState()
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val heartRateRepo = HeartRateRepository()
    private val passiveManager = PassiveMonitoringManager(application)
    private val wearDataSender = WearDataSender()

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _isPhoneConnected = MutableStateFlow(false)
    val isPhoneConnected: StateFlow<Boolean> = _isPhoneConnected.asStateFlow()

    private val _isSimulated = MutableStateFlow(false)
    val isSimulated: StateFlow<Boolean> = _isSimulated.asStateFlow()

    private var monitoringJob: Job? = null

    init {
        Wearable.getNodeClient(application).connectedNodes
            .addOnSuccessListener { nodes -> _isPhoneConnected.value = nodes.isNotEmpty() }

        // If a background session is still registered with Health Services from a
        // previous run, resume showing it so the user can see it and stop it.
        if (passiveManager.isBackgroundActive) {
            startSession()
        }
    }

    fun toggleDataSource() {
        val newValue = !_isSimulated.value
        _isSimulated.value = newValue
        // Clear the stale reading while the new source warms up.
        val current = _uiState.value as? HomeUiState.Monitoring
        if (current != null) {
            _uiState.value = current.copy(
                bpm = null, hrv = null, spo2 = null, stressIndex = null,
                readingsSent = 0, phoneReachable = null,
                isSimulated = newValue, isBackground = !newValue
            )
        }
    }

    fun startSession() {
        val sessionId = UUID.randomUUID().toString()
        _uiState.value = HomeUiState.Monitoring(
            bpm = null,
            hrv = null,
            spo2 = null,
            stressIndex = null,
            sessionId = sessionId,
            readingsSent = 0,
            isSimulated = _isSimulated.value,
            isBackground = !_isSimulated.value
        )

        // collectLatest cancels the active branch whenever the source flips, so
        // toggling Real/Fake mid-session hot-swaps without restarting.
        monitoringJob = viewModelScope.launch {
            _isSimulated.collectLatest { useSimulated ->
                if (useSimulated) {
                    // Fake: foreground simulation. Ensure background monitoring is off.
                    runCatching { passiveManager.stop() }
                    runSimulated()
                } else {
                    // Real: register background passive monitoring, then observe it.
                    runCatching { passiveManager.start() }
                        .onFailure { Log.e(TAG, "Failed to start passive monitoring", it) }
                    observePassive()
                }
            }
        }
    }

    // Foreground simulated stream — the ViewModel both displays and sends.
    private suspend fun runSimulated() {
        heartRateRepo.simulatedFlow().collect { reading ->
            val current = _uiState.value as? HomeUiState.Monitoring ?: return@collect
            _uiState.value = current.copy(
                bpm = reading.bpm,
                hrv = reading.hrv,
                spo2 = reading.spo2,
                stressIndex = reading.stressIndex,
                isSimulated = true,
                isBackground = false
            )
            val bundle = SensorBundle(
                bpm = reading.bpm.toInt(),
                hrv = reading.hrv,
                spo2 = reading.spo2,
                stressIndex = reading.stressIndex,
                timestamp = reading.timestamp,
                isSimulated = true
            )
            val ok = wearDataSender.send(getApplication(), bundle)
            val updated = _uiState.value as? HomeUiState.Monitoring ?: return@collect
            _uiState.value = updated.copy(phoneReachable = ok, readingsSent = updated.readingsSent + 1)
        }
    }

    // Real background stream — the PassiveDataService displays via the store and
    // sends to the phone; here we only mirror the store into the UI state.
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
                    readingsSent = sent,
                    phoneReachable = reachable,
                    isSimulated = false,
                    isBackground = true
                )
            }
    }

    fun stopSession() {
        monitoringJob?.cancel()
        monitoringJob = null
        // Explicit stop must end background monitoring too.
        viewModelScope.launch { runCatching { passiveManager.stop() } }
        _uiState.value = HomeUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        // Cancel only the in-process collector. We deliberately do NOT stop
        // passive monitoring here — that is what keeps real heart rate streaming
        // to the phone after the app is backgrounded or closed.
        monitoringJob?.cancel()
    }
}
