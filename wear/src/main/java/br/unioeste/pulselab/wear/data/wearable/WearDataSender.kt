package br.unioeste.pulselab.wear.data.wearable

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import br.unioeste.pulselab.wear.model.MessagePaths
import br.unioeste.pulselab.wear.model.SensorBundle
import kotlinx.coroutines.tasks.await

class WearDataSender {
    private val gson = Gson()

    suspend fun send(context: Context, bundle: SensorBundle): Boolean {
        return try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            if (nodes.isEmpty()) return false
            val payload = gson.toJson(bundle).toByteArray()
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, MessagePaths.SENSOR_DATA, payload)
                    .await()
            }
            true
        } catch (e: Exception) {
            Log.e("WearDataSender", "Failed to send sensor bundle", e)
            false
        }
    }
}
