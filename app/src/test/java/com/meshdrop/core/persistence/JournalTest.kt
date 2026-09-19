package com.meshdrop.core.persistence

import com.meshdrop.core.model.*
import com.meshdrop.core.resume.PartialFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JournalTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `queue restores immutable manifests and helper routes without an accepted state`() {
        val file = TransferFile("file", "photo", 8, localUri = "content://documents/1")
        val peer = PeerDevice("destination", "Phone", DiscoveryKind.USB, linkId = "usb:1")
        SendJournal(temp.root).put(QueuedSnapshot("batch", peer, listOf(file), listOf(listOf("a", "b"))))
        val restored = SendJournal(temp.root).all().single()
        assertEquals(file, restored.files.single())
        assertEquals(peer.id, restored.peer.id)
        assertEquals(listOf(listOf("a", "b")), restored.routes)
        assertFalse(File(temp.root, "outgoing.json").readText().contains("sessionKey"))
        SendJournal(temp.root).remove("batch")
        assertTrue(SendJournal(temp.root).all().isEmpty())
    }
    @Test fun `checksum rejects truncation and tampering`() {
        val path = File(temp.root, "journal.json"); val store = AtomicJson(path)
        store.write(JSONObject().put("a", 1))
        assertEquals(1, store.read()!!.getInt("a"))
        path.writeText(path.readText().replace("\\\"a\\\":1", "\\\"a\\\":2"))
        assertNull(store.read())
        path.writeText("{")
        assertNull(store.read())
    }
    @Test fun `partial restore never follows an arbitrary or escaping path`() {
        val partial = PartialFile(temp.root)
        partial.append(0, null).use { it.write("prefix".toByteArray()) }
        val restored = PartialFile.restore(temp.root, partial.file.name)!!
        assertEquals(6L, restored.size)
        assertEquals(partial.digest(), restored.digest())
        assertNull(PartialFile.restore(temp.root, "../secret"))
        assertNull(PartialFile.restore(temp.root, "/etc/passwd"))
        assertNull(PartialFile.restore(temp.root, "journal.json"))
    }
    @Test fun `expired or wrong batch journal cannot be recovered`() {
        val journal = ReceiveJournal(temp.root)
        journal.write("batch", JSONObject().put("touched", System.currentTimeMillis() - ReceiveJournal.RETENTION_MS - 1))
        assertNull(journal.read("batch"))
        assertNull(journal.read("another-batch"))
    }
}
