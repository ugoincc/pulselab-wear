package br.unioeste.pulselab.wear.data.health

import android.content.Context
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.HealthTrackerType

private const val TAG = "SensorConnectionMgr"

/**
 * Owns the connection to the Samsung Health Platform (handoff §4). Reads sensors
 * directly on the watch — does NOT go through Samsung Health or Health Connect.
 *
 * Requires Health Platform [Dev mode] on the device; without it the SDK reports
 * SDK_POLICY_ERROR (handoff §2 / §11).
 */
class SensorConnectionManager(context: Context) {

    private val appContext = context.applicationContext
    private var service: HealthTrackingService? = null

    /** Invoked once the platform is connected and capability can be queried. */
    var onConnected: (() -> Unit)? = null

    /** Invoked on connection failure with the raw exception. */
    var onError: ((HealthTrackerException) -> Unit)? = null

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            Log.d(TAG, "Health Platform connected — supported trackers: ${supportedTypes()}")
            onConnected?.invoke()
        }

        override fun onConnectionEnded() {
            Log.d(TAG, "Health Platform connection ended")
        }

        override fun onConnectionFailed(e: HealthTrackerException) {
            Log.e(TAG, "Connection failed code=${e.errorCode} hasResolution=${e.hasResolution()}", e)
            // e.resolve(activity) needs an Activity (e.g. to install/update the
            // Health Platform). We collect in the background from the ViewModel,
            // so surface the error and let the caller drive resolution if needed.
            onError?.invoke(e)
        }
    }

    fun connect() {
        service = HealthTrackingService(connectionListener, appContext)
        service?.connectService()
    }

    fun disconnect() {
        service?.disconnectService()
        service = null
    }

    /**
     * The definitive list of trackers available on THIS watch — the gate the
     * handoff (§5) insists on before instantiating any tracker. Empty until
     * [onConnected] has fired.
     */
    fun supportedTypes(): List<HealthTrackerType> =
        service?.trackingCapability?.supportHealthTrackerTypes ?: emptyList()

    fun isSupported(type: HealthTrackerType): Boolean = type in supportedTypes()

    /** Returns a tracker for [type], or null if not connected. */
    fun tracker(type: HealthTrackerType): HealthTracker? = service?.getHealthTracker(type)
}
