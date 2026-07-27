package br.unioeste.pulselab.wear.model

/**
 * The metrics the watch collects and displays, in the order they appear on screen.
 *
 * All six come from the Samsung Health Sensor SDK — directly (HR, SpO2, skin
 * temperature, EDA) or from our own maths over its raw output (HRV/RMSSD and the
 * Baevsky Stress Index, both derived from the IBI list). Nothing here is a
 * proprietary black-box score, and nothing here is simulated.
 *
 * [label] is intentionally short: it is rendered under an orb on a round watch
 * face, where there is room for a few characters and no more.
 */
enum class HealthMetric(val label: String, val unit: String) {
    HEART_RATE("FC", "bpm"),
    HRV("HRV", "ms"),
    STRESS_INDEX("SI", ""),
    SPO2("SpO2", "%"),
    SKIN_TEMP("Pele", "°C"),
    EDA("EDA", "µS")
}
