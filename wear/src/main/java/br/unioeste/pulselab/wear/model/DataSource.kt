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
 */
enum class DataSource(val label: String, val badge: String) {
    LIVE("Live · MeasureClient", "LIVE"),
    BACKGROUND("Background · Passive", "BG"),
    SIMULATED("Fake · Simulated", "SIM");

    fun next(): DataSource = entries[(ordinal + 1) % entries.size]
}
