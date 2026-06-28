package br.unioeste.pulselab.wear.model

data class HeartRateReading(
    val bpm: Double,
    val hrv: Int?,
    val spo2: Int?,
    val stressIndex: Int?,
    val skinTemp: Double? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isSimulated: Boolean = false
)
