package com.example.modules.vpn

import kotlin.test.*

class VpnConfigReuseTest {
    @Test fun failedNodeAndDifferentSelectionCannotReturnStaleConfig() {
        assertFalse(canReuseVpnConfig(false, false, true, "fin", "fra", "SINGLE", "SINGLE"))
        assertFalse(canReuseVpnConfig(false, true, true, "fin", "fra", "SINGLE", "SINGLE"))
        assertFalse(canReuseVpnConfig(false, true, false, "fra", "fra", "SINGLE", "SINGLE"))
        assertFalse(canReuseVpnConfig(false, true, true, "fra", "fra", "SINGLE", "CASCADE"))
        assertTrue(canReuseVpnConfig(false, true, true, "fra", "fra", "SINGLE", "SINGLE"))
    }
    @Test fun unavailableNodeDoesNotDelayReplacementOrClaimRemoteDeletion() {
        var attempts = 0; var pending = false
        retireVpnConfig(false, { attempts++ }, { pending = true })
        assertEquals(0, attempts); assertTrue(pending)
    }
    @Test fun failedDeletionIsQueuedRatherThanBlockingReplacement() {
        var pending = false
        retireVpnConfig(true, { error("node unreachable") }, { pending = true })
        assertTrue(pending)
    }
    @Test fun successfulDeletionNeedsNoDeferredTask() {
        var removed = false; var pending = false
        retireVpnConfig(true, { removed = true }, { pending = true })
        assertTrue(removed); assertFalse(pending)
    }
}
