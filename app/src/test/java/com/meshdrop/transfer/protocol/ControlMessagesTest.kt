package com.meshdrop.transfer.protocol

import org.junit.Assert.*
import org.junit.Test

class ControlMessagesTest {
    @Test fun `partial status round trips without confusing older receivers`() {
        val status = BatchStatus("batch", listOf("complete"), 12, mapOf("partial" to PartialStatus(65536, "a".repeat(64))))
        assertEquals(status, ControlMessages.parseBatchStatus(with(ControlMessages) { status.toJson() }))
        assertTrue(ControlMessages.parseBatchStatus("{\"batchId\":\"old\",\"completed\":[]}")!!.partialFiles.isEmpty())
    }

    @Test fun `invalid resume offsets and digests are ignored`() {
        val status = ControlMessages.parseBatchStatus("""{"batchId":"b","partials":{"f":{"bytes":-1,"sha256":"bad"}}}""")!!
        assertTrue(status.partialFiles.isEmpty())
    }

    @Test fun `verification gate is explicitly negotiated`() {
        val response = HandshakeResponse(HandshakeResponse.Decision.ACCEPTED, requiresVerification = true)
        assertEquals(response, ControlMessages.parseHandshakeResponse(with(ControlMessages) { response.toJson() }))
        assertFalse(ControlMessages.parseHandshakeResponse("{\"status\":\"accepted\"}")!!.requiresVerification)
    }

    @Test fun `malformed manifests are not partially accepted`() {
        fun offer(files: String) = """{"batchId":"batch","senderId":"s","files":$files}"""
        assertNull(ControlMessages.parseHandshake(offer("""[{"id":"f","name":"a","size":-1}]""")))
        assertNull(ControlMessages.parseHandshake(offer("""[{"id":"f","name":"a","size":1},{"id":"f","name":"b","size":2}]""")))
        assertNull(ControlMessages.parseHandshake(offer("""[{"id":"../f","name":"a","size":1}]""")))
        assertNull(ControlMessages.parseHandshake(offer("""[{"id":"f","name":"a","size":9223372036854775807},{"id":"g","name":"b","size":1}]""")))
    }
}
