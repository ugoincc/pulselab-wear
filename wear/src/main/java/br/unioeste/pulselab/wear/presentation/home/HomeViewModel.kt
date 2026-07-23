package br.unioeste.pulselab.wear.presentation.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.Wearable
import br.unioeste.pulselab.wear.data.health.SensorStreamService
import br.unioeste.pulselab.wear.data.health.SensorStreamStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed class HomeUiState {
    object Idle : HomeUiState()

    data class Monitoring(
        val bpm: Double?,
        val hrv: Int?,
        val spo2: Int?,
        val stressIndex: Int?,
        val skinTemp: Double?,
        val readingsSent: Int,
        val phoneReachable: Boolean? = null,
        val lastUpdate: Long? = null
    ) : HomeUiState()

    data class Error(val message: String) : HomeUiState()
}

/**
 * The watch is a relay: one source (Samsung Health Sensor SDK) streamed by
 * [SensorStreamService], which keeps collecting and forwarding to the phone
 * with the screen off. This ViewModel only mirrors the shared store into UI
 * state and starts/stops the service.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _isPhoneConnected = MutableStateFlow(false)
    val isPhoneConnected: StateFlow<Boolean> = _isPhoneConnected.asStateFlow()

    private var monitoringJob: Job? = null

    init {
        Wearable.getNodeClient(application).connectedNodes
            .addOnSuccessListener { nodes -> _isPhoneConnected.value = nodes.isNotEmpty() }

        // Mirror a service that is already running (app reopened mid-session).
        if (SensorStreamStore.isRunning.value) startSession()
    }

    fun startSession() {
        if (monitoringJob?.isActive == true) return
        _uiState.value = HomeUiState.Monitoring(
            bpm = null, hrv = null, spo2 = null, stressIndex = null, skinTemp = null,
            readingsSent = 0
        )
        SensorStreamService.start(getApplication())

        monitoringJob = viewModelScope.launch {
            combine(
                SensorStreamStore.latest,
                SensorStreamStore.readingsSent,
                SensorStreamStore.phoneReachable
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
                        lastUpdate = reading?.timestamp
                    )
                }
        }
    }

    fun stopSession() {
        monitoringJob?.cancel()
        monitoringJob = null
        SensorStreamService.stop(getApplication())
        SensorStreamStore.reset()
        _uiState.value = HomeUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        // Cancel only the UI mirror — the service keeps relaying until the user
        // explicitly stops it.
        monitoringJob?.cancel()
    }
}
