package br.unioeste.pulselab.wear.model

/**
 * The full set of metrics we intend to surface, and — importantly — where each
 * one's live value will actually come from. This is the "plan ahead" map.
 *
 * Reality check (Wear OS, standard AndroidX APIs):
 * - Only HEART_RATE is available live through Health Services
 *   (MeasureClient foreground, PassiveMonitoringClient background).
 * - HRV, SpO2, skin temperature, and stress are NOT real-time Health Services
 *   data types. They require Health Connect (stored history), the Samsung Health
 *   Sensor SDK (privileged, partner-gated), or on-device computation. Stress in
 *   particular is a Samsung-proprietary metric with no AOSP API.
 *
 * [wired] = we actually populate a real value today. The simulated source fills
 * all of these for UI testing; real sources only fill the wired ones.
 */
enum class HealthMetric(
    val label: String,
    val unit: String,
    val provider: String,
    val wired: Boolean
) {
    HEART_RATE(
        label = "HR",
        unit = "bpm",
        provider = "Health Services — MeasureClient (live) / PassiveMonitoringClient (bg)",
        wired = true
    ),
    HRV(
        label = "HRV",
        unit = "ms",
        provider = "Not in Health Services — compute from RR intervals or Samsung Health Sensor SDK",
        wired = false
    ),
    SPO2(
        label = "SpO2",
        unit = "%",
        provider = "On-demand only — Samsung Health Sensor SDK (privileged); Health Connect for history",
        wired = false
    ),
    SKIN_TEMP(
        label = "Skin Temp",
        unit = "°C",
        provider = "Samsung Health Sensor SDK (live); Health Connect SkinTemperatureRecord (API 35+)",
        wired = false
    ),
    STRESS(
        label = "Stress",
        unit = "/100",
        provider = "Samsung-proprietary (derived from HRV) — Samsung Health SDK, no AOSP API",
        wired = false
    )
}
