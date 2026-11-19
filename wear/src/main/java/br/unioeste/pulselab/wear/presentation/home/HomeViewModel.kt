package br.unioeste.pulselab.wear.presentation.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.Wearable
import br.unioeste.pulselab.wear.data.health.SensorStreamService
import br.unioeste.pulselab.wear.data.health.SensorStreamStore
import br.unioeste.pulselab.wear.data.provider.ProviderChoice
import br.unioeste.pulselab.wear.data.provider.ProviderSelection
import br.unioeste.pulselab.wear.model.SensorBundle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed class HomeUiState {
    object Idle : HomeUiState()

    /**
     * [reading] is null until the first sample arrives, which is why the screen
     * has to render empty orbs rather than wait for data.
     */
    data class Monitoring(
        val reading: SensorBundle?,
        val phoneReachable: Boolean? = null
    ) : HomeUiState()

    data class Error(val message: String) : HomeUiState()
}

/**
 * The watch is a relay: one selected [br.unioeste.pulselab.wear.data.provider.SensorProvider]
 * streamed by [SensorStreamService], which keeps collecting and forwarding to
 * the phone with the screen off. This ViewModel only mirrors the shared store
 * into UI state and starts/stops the service.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _isPhoneConnected = MutableStateFlow(false)
    val isPhoneConnected: StateFlow<Boolean> = _isPhoneConnected.asStateFlow()

    /**
     * Which source the service is pointed at. Read from storage rather than
     * defaulted, so reopening the app mid-run shows what is actually streaming.
     */
    private val _provider = MutableStateFlow(ProviderSelection.selected(application))
    val provider: StateFlow<ProviderChoice> = _provider.asStateFlow()

    private var monitoringJob: Job? = null

    init {
        Wearable.getNodeClient(application).connectedNodes
            .addOnSuccessListener { nodes -> _isPhoneConnected.value = nodes.isNotEmpty() }

        // Mirror a service that is already running (app reopened mid-session).
        if (SensorStreamStore.isRunning.value) startSession()
    }

    /**
     * @param provider null keeps the persisted selection — the normal path.
     *   An explicit choice is how an evaluation run points the service at a
     *   different source without a rebuild.
     */
    fun startSession(provider: ProviderChoice? = null) {
        if (monitoringJob?.isActive == true && provider == null) return
        _uiState.value = HomeUiState.Monitoring(reading = null)
        SensorStreamService.start(getApplication(), provider)

        monitoringJob = viewModelScope.launch {
            combine(
                SensorStreamStore.latest,
                SensorStreamStore.phoneReachable
            ) { reading, reachable -> reading to reachable }
                .collect { (reading, reachable) ->
                    val current = _uiState.value as? HomeUiState.Monitoring ?: return@collect
                    _uiState.value = current.copy(
                        reading = reading,
                        phoneReachable = reachable
                    )
                }
        }
    }

    /**
     * Cycles to the next provider and restarts collection on it.
     *
     * Present so the acceptance criterion of Objetivo 4 can be exercised on
     * device: switching source has to change the `providerId` and the set of
     * populated fields with nothing downstream changing. It is also how an
     * evaluation run points the watch at the deterministic provider without a
     * rebuild.
     */
    fun cycleProvider() {
        val next = ProviderChoice.entries[
            (ProviderChoice.entries.indexOf(_provider.value) + 1) % ProviderChoice.entries.size
        ]
        _provider.value = next
        // Clear the mirrored reading: the next sample comes from a different
        // source, and leaving the previous provider's values on screen would
        // read as if the new one had already produced them.
        SensorStreamStore.reset()
        if (monitoringJob?.isActive == true) {
            SensorStreamService.start(getApplication(), next)
        } else {
            ProviderSelection.select(getApplication(), next)
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
