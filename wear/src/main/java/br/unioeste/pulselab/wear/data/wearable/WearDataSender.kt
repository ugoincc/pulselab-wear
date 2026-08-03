package br.unioeste.pulselab.wear.data.wearable

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import br.unioeste.pulselab.wear.data.provider.CapabilityAnnouncement
import br.unioeste.pulselab.wear.model.MessagePaths
import br.unioeste.pulselab.wear.model.SensorBatch
import kotlinx.coroutines.tasks.await

class WearDataSender {
    private val gson = Gson()

    /** Sends one burst as a single Data Layer message. Returns false if no node took it. */
    suspend fun send(context: Context, batch: SensorBatch): Boolean {
        if (batch.samples.isEmpty()) return true
        return try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            if (nodes.isEmpty()) {
                Log.w("WearDataSender", "No connected Data Layer nodes — is the phone paired and reachable?")
                return false
            }
            val payload = gson.toJson(batch).toByteArray()
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, MessagePaths.SENSOR_BATCH, payload)
                    .await()
            }
            Log.d("WearDataSender", "Sent batch of ${batch.samples.size} samples (${payload.size} B)")
            true
        } catch (e: Exception) {
            Log.e("WearDataSender", "Failed to send sensor batch", e)
            false
        }
    }

    /**
     * Sends a [CapabilityAnnouncement] on [MessagePaths.SENSOR_CAPABILITIES] —
     * meant to be called once per provider connection, not per sample
     * (ESPECIFICACAO-protocolo-payload-v1.md §6.3). Not called from any
     * production path yet — see the comment on [MessagePaths.SENSOR_CAPABILITIES].
     */
    suspend fun sendCapabilities(context: Context, announcement: CapabilityAnnouncement): Boolean {
        return try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            if (nodes.isEmpty()) {
                Log.w("WearDataSender", "No connected Data Layer nodes — is the phone paired and reachable?")
                return false
            }
            val payload = gson.toJson(announcement).toByteArray()
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, MessagePaths.SENSOR_CAPABILITIES, payload)
                    .await()
            }
            Log.d("WearDataSender", "Sent capability announcement for ${announcement.providerId}")
            true
        } catch (e: Exception) {
            Log.e("WearDataSender", "Failed to send capability announcement", e)
            false
        }
    }
}
