package com.meshdrop.core.security

/** Discovery names and device IDs never grant permission to receive files automatically. */
object TransferApprovalPolicy {
    fun canAutoAccept(enabled: Boolean, encrypted: Boolean, signatureValid: Boolean, remembered: Boolean): Boolean =
        enabled && encrypted && signatureValid && remembered

    fun mayTransfer(requireVerification: Boolean, encrypted: Boolean, verified: Boolean): Boolean =
        !requireVerification || (encrypted && verified)
}
