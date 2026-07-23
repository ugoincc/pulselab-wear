package br.unioeste.pulselab.wear.data.health

import br.unioeste.pulselab.wear.model.HeartRateReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide bridge between [SensorStreamService] and the UI, mirroring the
 * [PassiveHeartRateStore] pattern: the service streams Samsung SDK readings and
 * forwards them to the phone whether or not any UI is observing; the ViewModel
 * mirrors these flows into screen state when the app is open.
 */
object SensorStreamStore {

    private val _latest = MutableStateFlow<HeartRateReading?>(null)
    val latest: StateFlow<HeartRateReading?> = _latest.asStateFlow()

    private val _readingsSent = MutableStateFlow(0)
    val readingsSent: StateFlow<Int> = _readingsSent.asStateFlow()

    private val _phoneReachable = MutableStateFlow<Boolean?>(null)
    val phoneReachable: StateFlow<Boolean?> = _phoneReachable.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun update(reading: HeartRateReading) {
        _latest.value = reading
    }

    fun recordSend(success: Boolean) {
        _phoneReachable.value = success
        if (success) _readingsSent.value += 1
    }

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    fun reset() {
        _latest.value = null
        _readingsSent.value = 0
        _phoneReachable.value = null
    }
}
