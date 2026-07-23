package br.unioeste.pulselab.wear.presentation.bia

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.unioeste.pulselab.wear.data.health.BiaMeasureState
import br.unioeste.pulselab.wear.data.health.BiaMeasurementManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Screen-level state for the guided BIA flow. */
sealed class BiaUiState {
    /** Step-by-step instructions before the measurement starts. */
    object Instructions : BiaUiState()

    data class Measuring(val progress: Float, val contactLost: Boolean) : BiaUiState()

    data class Done(val result: br.unioeste.pulselab.wear.data.health.BiaResult) : BiaUiState()

    data class Failed(val reason: String) : BiaUiState()
}

private const val MEASUREMENT_TIMEOUT_MS = 90_000L

class BiaViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = BiaMeasurementManager(application)

    private val _uiState = MutableStateFlow<BiaUiState>(BiaUiState.Instructions)
    val uiState: StateFlow<BiaUiState> = _uiState.asStateFlow()

    private var measureJob: Job? = null

    fun startMeasurement() {
        _uiState.value = BiaUiState.Measuring(progress = 0f, contactLost = false)

        measureJob?.cancel()
        measureJob = viewModelScope.launch {
            val timeout = launch {
                delay(MEASUREMENT_TIMEOUT_MS)
                _uiState.value = BiaUiState.Failed("Tempo esgotado — tente novamente")
                measureJob?.cancel()
            }
            manager.measure().collect { state ->
                when (state) {
                    is BiaMeasureState.Connecting ->
                        _uiState.value = BiaUiState.Measuring(0f, contactLost = false)
                    is BiaMeasureState.Measuring ->
                        _uiState.value = BiaUiState.Measuring(state.progress, state.contactLost)
                    is BiaMeasureState.Done -> {
                        timeout.cancel()
                        _uiState.value = BiaUiState.Done(state.result)
                    }
                    is BiaMeasureState.Failed -> {
                        timeout.cancel()
                        _uiState.value = BiaUiState.Failed(state.reason)
                    }
                }
            }
            timeout.cancel()
        }
    }

    fun reset() {
        measureJob?.cancel()
        _uiState.value = BiaUiState.Instructions
    }

    override fun onCleared() {
        super.onCleared()
        measureJob?.cancel()
    }
}
