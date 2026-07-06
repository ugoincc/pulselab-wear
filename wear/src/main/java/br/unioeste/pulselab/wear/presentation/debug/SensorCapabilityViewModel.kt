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
