package com.meshdrop.cli

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopStorageTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `desktop identity and trust persist across instances`() {
        val first = FileIdentity(temp.root)
        val second = FileIdentity(temp.root)
        assertEquals(first.deviceId, second.deviceId)
        assertArrayEquals(first.publicKeyBytes, second.publicKeyBytes)
        assertTrue(com.meshdrop.transfer.crypto.IdentityVerifier.verify(first.publicKeyBytes, "proof".toByteArray(), second.sign("proof".toByteArray())))
        FileTrust(temp.root).remember("full-fingerprint", "Phone")
        assertTrue(FileTrust(temp.root).isVerified("full-fingerprint"))
    }
    @Test fun `corrupt identity never silently rotates the signing key`() {
        FileIdentity(temp.root)
        File(temp.root, "identity.json").writeText("broken")
        try { FileIdentity(temp.root); fail("Silently rotated corrupt identity") } catch (_: IllegalStateException) {}
    }
    @Test fun `desktop sink sanitizes paths and does not overwrite existing files`() {
        val output = temp.newFolder("downloads")
        val store = DesktopFileStore(output, temp.root)
        val a = store.createSink("../CON.bin", null)
        a.outputStream.write(byteArrayOf(1)); a.finish()
        val b = store.createSink("../CON.bin", null)
        b.outputStream.write(byteArrayOf(2)); b.finish()
        assertEquals(2, output.listFiles()!!.size)
        assertNotEquals(a.displayName, b.displayName)
        assertEquals(1, File(output, a.displayName).readBytes().single().toInt())
    }
}
