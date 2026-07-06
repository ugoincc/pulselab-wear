package br.unioeste.pulselab.wear.data.health.debug

import android.util.Log
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import br.unioeste.pulselab.wear.data.health.SensorConnectionManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** One raw PPG channel sample (green, red or ir — same shape for all three). */
data class PpgReading(val value: Int?, val status: Int?, val timestamp: Long)

/**
 * Shared continuous-tracker plumbing for the three raw PPG channels
 * ([HealthTrackerType.PPG_GREEN]/[HealthTrackerType.PPG_RED]/[HealthTrackerType.PPG_IR]).
 * None of these types has an on-demand variant in the SDK, so all three
 * start/stop with the caller's lifecycle.
 */
private fun ppgReadingFlow(
    tag: String,
    trackerType: HealthTrackerType,
    tracker: SensorTracker<PpgReading>,
    connection: SensorConnectionManager,
    channel: kotlinx.coroutines.channels.ProducerScope<PpgReading>,
    value: (DataPoint) -> Int?,
    status: (DataPoint) -> Int?
) {
    if (!tracker.isSupported(connection)) {
        Log.w(tag, "$trackerType not supported on this device")
        channel.close()
        return
    }

    val listener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                channel.trySend(PpgReading(value(dp), status(dp), dp.getTimestamp()))
            }
        }

        override fun onFlushCompleted() {}
        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(tag, "$trackerType tracker error: $error")
            channel.close()
        }
    }

    val healthTracker = connection.tracker(trackerType)?.also { it.setEventListener(listener) }
    channel.invokeOnClose { healthTracker?.unsetEventListener() }
}

private const val PPG_GREEN_TAG = "PpgGreenTracker"

/** Reads the raw green PPG channel ([ValueKey.PpgGreenSet]). Continuous only. */
class PpgGreenTracker : SensorTracker<PpgReading> {
    override val trackerType: HealthTrackerType = HealthTrackerType.PPG_GREEN
    override val isContinuous: Boolean = true

    override fun readingFlow(connection: SensorConnectionManager): Flow<PpgReading> =
        callbackFlow {
            ppgReadingFlow(
                tag = PPG_GREEN_TAG,
                trackerType = trackerType,
                tracker = this@PpgGreenTracker,
                connection = connection,
                channel = this,
                value = { it.getValue(ValueKey.PpgGreenSet.PPG_GREEN) },
                status = { it.getValue(ValueKey.PpgGreenSet.STATUS) }
            )
            awaitClose {}
        }
}

private const val PPG_RED_TAG = "PpgRedTracker"

/** Reads the raw red PPG channel ([ValueKey.PpgRedSet]). Continuous only. */
class PpgRedTracker : SensorTracker<PpgReading> {
    override val trackerType: HealthTrackerType = HealthTrackerType.PPG_RED
    override val isContinuous: Boolean = true

    override fun readingFlow(connection: SensorConnectionManager): Flow<PpgReading> =
        callbackFlow {
            ppgReadingFlow(
                tag = PPG_RED_TAG,
                trackerType = trackerType,
                tracker = this@PpgRedTracker,
                connection = connection,
                channel = this,
                value = { it.getValue(ValueKey.PpgRedSet.PPG_RED) },
                status = { it.getValue(ValueKey.PpgRedSet.STATUS) }
            )
            awaitClose {}
        }
}

private const val PPG_IR_TAG = "PpgIrTracker"

/** Reads the raw infrared PPG channel ([ValueKey.PpgIrSet]). Continuous only. */
class PpgIrTracker : SensorTracker<PpgReading> {
    override val trackerType: HealthTrackerType = HealthTrackerType.PPG_IR
    override val isContinuous: Boolean = true

    override fun readingFlow(connection: SensorConnectionManager): Flow<PpgReading> =
        callbackFlow {
            ppgReadingFlow(
                tag = PPG_IR_TAG,
                trackerType = trackerType,
                tracker = this@PpgIrTracker,
                connection = connection,
                channel = this,
                value = { it.getValue(ValueKey.PpgIrSet.PPG_IR) },
                status = { it.getValue(ValueKey.PpgIrSet.STATUS) }
            )
            awaitClose {}
        }
}
