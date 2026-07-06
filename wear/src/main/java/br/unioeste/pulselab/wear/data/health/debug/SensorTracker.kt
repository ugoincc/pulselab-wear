package br.unioeste.pulselab.wear.data.health.debug

import com.samsung.android.service.health.tracking.data.HealthTrackerType
import br.unioeste.pulselab.wear.data.health.SensorConnectionManager
import kotlinx.coroutines.flow.Flow

/**
 * Wraps one Samsung [HealthTrackerType] as an isolated, readable debug metric,
 * so each new sensor can be added as its own class instead of growing one
 * monolithic monitor (mirrors the per-tracker structure in SamsungSensorMonitor.kt,
 * but kept separate from it so that file is never touched by debug additions).
 */
interface SensorTracker<T> {

    /** The Samsung Health Sensor SDK tracker type this class reads from. */
    val trackerType: HealthTrackerType

    /**
     * True for tracker types that stream continuously once started (e.g.
     * HEART_RATE_CONTINUOUS); false for on-demand types that must be explicitly
     * triggered and yield a single measurement per trigger (e.g. SPO2_ON_DEMAND).
     */
    val isContinuous: Boolean

    /** Whether [connection]'s capability report includes [trackerType]. */
    fun isSupported(connection: SensorConnectionManager): Boolean =
        trackerType in connection.supportedTypes()

    /**
     * Starts reading [trackerType] from [connection] and emits typed readings.
     * Continuous trackers emit for as long as the flow is collected; on-demand
     * trackers emit the result of one measurement and then complete.
     */
    fun readingFlow(connection: SensorConnectionManager): Flow<T>
}
