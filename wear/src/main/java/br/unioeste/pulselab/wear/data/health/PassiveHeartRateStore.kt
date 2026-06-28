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
