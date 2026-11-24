package nl.meereboer.ebikelive.wear

import android.content.Context
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable

/** Sends volume up/down to the eBike live app on the paired phone. */
object PhoneVolume {
    const val PATH = "/ebike/volume"
    private var nodes: List<Node> = emptyList()
    private var lastLookup = 0L

    fun step(ctx: Context, up: Boolean) {
        val payload = byteArrayOf(if (up) 1 else 0)
        val now = System.currentTimeMillis()
        if (nodes.isNotEmpty() && now - lastLookup < 60_000) {
            send(ctx, payload)
            return
        }
        Wearable.getNodeClient(ctx).connectedNodes.addOnSuccessListener { found ->
            nodes = found
            lastLookup = now
            send(ctx, payload)
        }
    }

    private fun send(ctx: Context, payload: ByteArray) {
        val client = Wearable.getMessageClient(ctx)
        nodes.forEach { client.sendMessage(it.id, PATH, payload) }
    }
}
