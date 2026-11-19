package br.unioeste.pulselab.wear.data.health

import br.unioeste.pulselab.wear.model.SensorBundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide bridge between [SensorStreamService] and the UI: the service
 * collects from the selected
 * [br.unioeste.pulselab.wear.data.provider.SensorProvider] and forwards to the phone
 * whether or not any UI is observing; the ViewModel mirrors these flows into
 * screen state when the app is open.
 *
 * Holds [SensorBundle], the wire payload, rather than `SensorReading`: since the
 * service went through `SensorProvider`, that is the one shape every provider
 * produces — `SensorReading` is now internal to the Samsung path.
 */
object SensorStreamStore {

    private val _latest = MutableStateFlow<SensorBundle?>(null)
    val latest: StateFlow<SensorBundle?> = _latest.asStateFlow()

    private val _phoneReachable = MutableStateFlow<Boolean?>(null)
    val phoneReachable: StateFlow<Boolean?> = _phoneReachable.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun update(reading: SensorBundle) {
        _latest.value = reading
    }

    fun recordSend(success: Boolean) {
        _phoneReachable.value = success
    }

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    fun reset() {
        _latest.value = null
        _phoneReachable.value = null
    }
}
