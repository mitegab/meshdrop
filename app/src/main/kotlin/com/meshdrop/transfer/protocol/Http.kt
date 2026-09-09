package com.meshdrop.transfer.protocol

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * The smallest thing that can honestly be called HTTP/1.1.
 *
 * MeshDrop only needs request/response framing plus `Content-Length`, so instead of pulling
 * in OkHttp or Ktor we read and write the handful of bytes ourselves. That keeps the MVP
 * dependency-free, makes the protocol inspectable with `nc`/`curl`, and means a future
 * desktop client can be written in any language.
 *
 * Rules of our dialect:
 *  * one request per TCP connection (`Connection: close`) — no keep-alive state machine
 *  * bodies are always framed with `Content-Length` (never chunked)
 *  * JSON for control messages, raw bytes for file payloads
 */
object Http {

    const val CRLF = "\r\n"
    private const val MAX_HEADER_BYTES = 32 * 1024

    data class Response(
        val status: Int,
        val reason: String,
        private val rawHeaders: Map<String, String>,
        val body: ByteArray,
    ) {
        val isSuccessful: Boolean get() = status in 200..299

        /** Headers are matched case-insensitively, as required by RFC 9110. */
        fun header(name: String): String? = rawHeaders[name.lowercase()]

        fun bodyText(): String = String(body, StandardCharsets.UTF_8)
    }

    data class RequestHead(
        val method: String,
        val path: String,
        val query: String,
        private val rawHeaders: Map<String, String>,
        val contentLength: Long,
    ) {
        fun header(name: String): String? = rawHeaders[name.lowercase()]

        /** Path segments after the fixed prefix, e.g. `/meshdrop/upload/b42/0` → [b42, 0]. */
        fun segments(): List<String> = path.trim('/').split('/')
    }

    // region client side ------------------------------------------------------------------

    /**
     * Writes a request line + headers. Called with a known [contentLength] so large uploads
     * can be streamed straight from the document provider without buffering the file.
     */
    fun OutputStream.writeRequestHead(
        method: String,
        path: String,
        contentLength: Long,
        headers: Map<String, String> = emptyMap(),
    ) {
        val head = buildString {
            append(method).append(' ').append(path).append(" HTTP/1.1").append(CRLF)
            append("Host: ").append(DEFAULT_HOST).append(CRLF)
            headers.forEach { (name, value) ->
                append(name).append(": ").append(value).append(CRLF)
            }
            append("Content-Length: ").append(contentLength).append(CRLF)
            append("Connection: close").append(CRLF)
            append(CRLF)
        }
        write(head.toByteArray(StandardCharsets.US_ASCII))
        flush()
    }

    /** Writes a complete small request (handshake, ping, completion). */
    fun OutputStream.writeRequest(
        method: String,
        path: String,
        body: ByteArray = ByteArray(0),
        contentType: String = CONTENT_TYPE_JSON,
        headers: Map<String, String> = emptyMap(),
    ) {
        val allHeaders = HashMap(headers)
        if (body.isNotEmpty()) allHeaders["Content-Type"] = contentType
        writeRequestHead(method, path, body.size.toLong(), allHeaders)
        if (body.isNotEmpty()) write(body)
        flush()
    }

    @Throws(IOException::class)
    fun BufferedInputStream.readResponse(): Response {
        val statusLine = readLineCrlf() ?: throw IOException("Connection closed before response")
        val parts = statusLine.split(' ', limit = 3)
        if (parts.size < 2) throw IOException("Malformed status line: $statusLine")
        val status = parts[1].toIntOrNull() ?: throw IOException("Malformed status code: $statusLine")
        val headers = readHeaders()
        val length = headers["content-length"]?.toLongOrNull()
        val body = when {
            length != null && length >= 0 -> readExactly(length)
            else -> readBytes()
        }
        return Response(status, parts.getOrNull(2).orEmpty(), headers, body)
    }

    // endregion

    // region server side ------------------------------------------------------------------

    @Throws(IOException::class)
    fun BufferedInputStream.readRequestHead(): RequestHead? {
        val requestLine = readLineCrlf() ?: return null
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size < 2) throw IOException("Malformed request line: $requestLine")
        val target = parts[1]
        val queryIndex = target.indexOf('?')
        val path = if (queryIndex >= 0) target.substring(0, queryIndex) else target
        val query = if (queryIndex >= 0) target.substring(queryIndex + 1) else ""
        val headers = readHeaders()
        return RequestHead(
            method = parts[0].uppercase(),
            path = path,
            query = query,
            rawHeaders = headers,
            contentLength = headers["content-length"]?.toLongOrNull() ?: 0L,
        )
    }

    fun OutputStream.writeResponse(
        status: Int,
        reason: String,
        body: ByteArray = ByteArray(0),
        contentType: String = CONTENT_TYPE_JSON,
        headers: Map<String, String> = emptyMap(),
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append(CRLF)
            append("Content-Type: ").append(if (body.isEmpty()) CONTENT_TYPE_JSON else contentType).append(CRLF)
            append("Content-Length: ").append(body.size).append(CRLF)
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append(CRLF) }
            append("Cache-Control: no-store").append(CRLF)
            append("Connection: close").append(CRLF)
            append(CRLF)
        }
        write(head.toByteArray(StandardCharsets.US_ASCII))
        if (body.isNotEmpty()) write(body)
        flush()
    }

    fun OutputStream.writeJson(status: Int, reason: String, payload: String) =
        writeResponse(status, reason, payload.toByteArray(StandardCharsets.UTF_8), CONTENT_TYPE_JSON)

    fun OutputStream.writeError(status: Int, reason: String, message: String) =
        writeJson(status, reason, ControlMessages.error(message))

    // endregion

    // region shared helpers ---------------------------------------------------------------

    /**
     * Copies [length] bytes from this stream to [out], invoking [onProgress] with the number
     * of bytes copied so far. Returns the total number of bytes copied.
     */
    fun BufferedInputStream.copyToWithProgress(
        out: OutputStream,
        length: Long,
        bufferSize: Int = TransferProtocol.CHUNK_BYTES,
        onProgress: (copied: Long) -> Unit,
    ): Long {
        val buffer = ByteArray(bufferSize)
        var remaining = length
        var copied = 0L
        while (remaining > 0L) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val read = read(buffer, 0, toRead)
            if (read == -1) break
            out.write(buffer, 0, read)
            remaining -= read
            copied += read
            onProgress(copied)
        }
        out.flush()
        return copied
    }

    /** Reads exactly [count] bytes, or throws if the peer hangs up early. */
    fun BufferedInputStream.readExactly(count: Long): ByteArray {
        if (count <= 0L) return ByteArray(0)
        require(count <= Int.MAX_VALUE) { "Body too large: $count bytes" }
        val result = ByteArray(count.toInt())
        var offset = 0
        while (offset < result.size) {
            val read = read(result, offset, result.size - offset)
            if (read == -1) throw IOException("Connection closed after $offset of $count bytes")
            offset += read
        }
        return result
    }

    private fun BufferedInputStream.readHeaders(): Map<String, String> {
        val headers = HashMap<String, String>()
        var bytesRead = 0
        while (true) {
            val line = readLineCrlf() ?: break
            bytesRead += line.length
            if (line.isEmpty()) break
            if (bytesRead > MAX_HEADER_BYTES) throw IOException("Header section too large")
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            headers[name] = value
        }
        return headers
    }

    /** Reads up to the next `\n`, tolerating a trailing `\r`. */
    private fun BufferedInputStream.readLineCrlf(): String? {
        val buffer = ByteArrayOutputStream(128)
        while (true) {
            val byte = read()
            if (byte == -1) return if (buffer.size() == 0) null else buffer.toString(StandardCharsets.UTF_8.name())
            if (byte == '\n'.code) return buffer.toString(StandardCharsets.UTF_8.name()).trimEnd('\r')
            buffer.write(byte)
        }
    }

    const val CONTENT_TYPE_JSON = "application/json"
    const val CONTENT_TYPE_OCTET = "application/octet-stream"
    private const val DEFAULT_HOST = "meshdrop.local"
}
