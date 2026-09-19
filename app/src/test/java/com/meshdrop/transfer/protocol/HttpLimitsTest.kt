package com.meshdrop.transfer.protocol

import com.meshdrop.transfer.protocol.Http.readRequestHead
import com.meshdrop.transfer.protocol.Http.readResponse
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.IOException

class HttpLimitsTest {
    private fun input(text: String) = BufferedInputStream(ByteArrayInputStream(text.toByteArray()))

    @Test fun `invalid request lengths and chunked framing fail closed`() {
        for (header in listOf("Content-Length: -1", "Content-Length: nope", "Transfer-Encoding: chunked")) {
            assertThrows(IOException::class.java) { input("PUT /file HTTP/1.1\r\n$header\r\n\r\n").readRequestHead() }
        }
    }

    @Test fun `response cannot allocate an unbounded body`() {
        assertThrows(IOException::class.java) { input("HTTP/1.1 200 OK\r\nContent-Length: 2147483647\r\n\r\n").readResponse() }
        assertThrows(IOException::class.java) { input("HTTP/1.1 200 OK\r\n\r\n").readResponse() }
    }

    @Test fun `single oversized header is bounded while reading`() {
        assertThrows(IOException::class.java) { input("GET / HTTP/1.1\r\nX: ${"a".repeat(40_000)}\r\n\r\n").readRequestHead() }
    }
}
