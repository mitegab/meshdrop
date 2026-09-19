package com.meshdrop.transfer.mesh

import com.meshdrop.transfer.protocol.Http
import com.meshdrop.transfer.protocol.Http.readExactly
import com.meshdrop.transfer.protocol.Http.writeError
import com.meshdrop.transfer.protocol.Http.writeJson
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream

/** Only two bounded JSON bodies may be decoded concurrently, including unauthenticated offers. */
class MeshService(val receiver: MeshReceiver, val relay: RelayService) {
    private val slots = Semaphore(2)
    suspend fun handle(head: Http.RequestHead, input: BufferedInputStream, output: BufferedOutputStream): Boolean {
        if (!head.path.startsWith(MeshRpc.PREFIX) && !head.path.startsWith(MeshRpc.RELAY)) return false
        if (head.method != "POST" || !head.hasContentLength || head.contentLength !in 2..MeshRpc.MAX_BYTES.toLong()) {
            output.writeError(400, "Bad Request", "Expected a bounded mesh JSON request"); return true
        }
        slots.withPermit {
        val body = JSONObject(input.readExactly(head.contentLength).toString(Charsets.UTF_8))
        try {
            val result = if (head.path.startsWith(MeshRpc.RELAY)) relay.handle(head.path.removePrefix(MeshRpc.RELAY), body)
                else receiver.handle(head.path.removePrefix(MeshRpc.PREFIX), body)
            output.writeJson(200, "OK", result.toString())
        } catch (error: Exception) { output.writeError(403, "Forbidden", error.message ?: "Mesh request rejected") }
        }
        return true
    }
    fun stop() { receiver.stop(); relay.revokeAll() }
    fun cancel(id: String) = receiver.cancel(id)
}
