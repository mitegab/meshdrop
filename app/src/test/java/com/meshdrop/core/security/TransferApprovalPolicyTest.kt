package com.meshdrop.core.security

import org.junit.Assert.*
import org.junit.Test

class TransferApprovalPolicyTest {
    @Test fun `auto accept needs encryption and authenticated remembered identity`() {
        assertTrue(TransferApprovalPolicy.canAutoAccept(true, true, true, true))
        for (missing in 0..3) {
            val flags = MutableList(4) { true }.also { it[missing] = false }
            assertFalse(TransferApprovalPolicy.canAutoAccept(flags[0], flags[1], flags[2], flags[3]))
        }
    }

    @Test fun `required verification blocks plaintext and unknown peers`() {
        assertTrue(TransferApprovalPolicy.mayTransfer(false, false, false))
        assertFalse(TransferApprovalPolicy.mayTransfer(true, false, true))
        assertFalse(TransferApprovalPolicy.mayTransfer(true, true, false))
        assertTrue(TransferApprovalPolicy.mayTransfer(true, true, true))
    }
}
