package com.meshdrop.transfer.crypto

/** Storage for [com.meshdrop.transfer.crypto.SessionNegotiator]; the JVM tests use [InMemoryTrustedPeers]. */
interface TrustedPeers {
    fun isVerified(fingerprint: String): Boolean
    fun remember(fingerprint: String, label: String)
    fun forget(fingerprint: String)
}

/** In-memory trust store used by unit tests. */
class InMemoryTrustedPeers : TrustedPeers {
    private val remembered = LinkedHashMap<String, String>()

    override fun isVerified(fingerprint: String): Boolean = remembered.containsKey(fingerprint)
    override fun remember(fingerprint: String, label: String) { remembered[fingerprint] = label }
    override fun forget(fingerprint: String) { remembered.remove(fingerprint) }
}

