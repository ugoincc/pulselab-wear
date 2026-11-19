package br.unioeste.pulselab.wear.model

/**
 * The metrics the watch collects and displays, in the order they appear on screen.
 *
 * All five come from the Samsung Health Sensor SDK — directly (HR, SpO2, skin
 * temperature) or from our own maths over its raw output (HRV/RMSSD and the
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
    SKIN_TEMP("Pele", "°C");

    companion object {
        /**
         * EDA was the fourth feature of the composite index and is kept out of the
         * enum, not deleted: `EDA_CONTINUOUS` is absent from the Galaxy Watch
         * Ultra's capability list, so an orb for it could only ever render empty.
         *
         * The plumbing behind it stays in the tree — [br.unioeste.pulselab.wear.data.health.EdaWindow],
         * the tracker wiring in SamsungSensorMonitor and the `edaTonic`/`edaScrPerMin`
         * fields on the wire — because it is hardware availability that is missing,
         * not code. On a watch that exposes the tracker, restoring this entry is
         * the only change needed here.
         *
         * See the methodological caveat in SENSORS.md: without EDA the index runs
         * on RMSSD and SI alone, and both derive from the same IBI signal.
         */
        const val EDA_UNAVAILABLE_NOTE = "EDA_CONTINUOUS não existe no Galaxy Watch Ultra"
    }
}
