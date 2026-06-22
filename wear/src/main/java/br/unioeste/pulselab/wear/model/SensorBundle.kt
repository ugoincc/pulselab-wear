package br.unioeste.pulselab.wear.model

data class SensorBundle(
    val bpm: Int,
    val hrv: Int?,
    val spo2: Int?,
    val stressIndex: Int?,
    val timestamp: Long,
    val isSimulated: Boolean = false
)
