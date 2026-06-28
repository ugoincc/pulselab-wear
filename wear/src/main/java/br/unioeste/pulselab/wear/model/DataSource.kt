package br.unioeste.pulselab.wear.model

/**
 * The mechanism currently feeding readings. Exposed explicitly (instead of a
 * real/fake boolean) so the dev/debug screen can show exactly which Health
 * Services path is active.
 *
 * - [LIVE]: MeasureClient — real heart rate, foreground only, per-second updates.
 * - [BACKGROUND]: PassiveMonitoringClient — real heart rate, batched, survives
 *   backgrounding and process death.
 * - [SIMULATED]: in-app fake feed that fills every metric (for UI testing).
 * - [SENSOR]: Samsung Health Sensor SDK — real HR + IBI (→ HRV/RMSSD), SpO2 and
 *   skin temperature, foreground. Reads sensors directly on the watch.
 */
enum class DataSource(val label: String, val badge: String) {
    LIVE("Live · MeasureClient", "LIVE"),
    BACKGROUND("Background · Passive", "BG"),
    SIMULATED("Fake · Simulated", "SIM"),
    SENSOR("Sensor · Samsung SDK", "SHS");

    fun next(): DataSource = entries[(ordinal + 1) % entries.size]
}
