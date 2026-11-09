package com.example.modules.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GuestNodeSelectorTest {
    @Test fun failedDefaultFallsBackToRestrictedNode() {
        assertEquals("fra-guest", selectGuestNode(listOf("ned-guest", "fra-guest"), null) {
            it.takeIf { it == "fra-guest" }
        })
    }
    @Test fun callerCannotEscapeGuestAllowlist() {
        val visited = mutableListOf<String>()
        assertNull(selectGuestNode(listOf("ned-guest"), "fra-full") { visited += it; null })
        assertEquals(listOf("ned-guest"), visited)
    }
    @Test fun requestedHealthyGuestIsRespected() {
        assertEquals("fra-guest", selectGuestNode(listOf("ned-guest", "fra-guest"), "fra-guest") { it })
    }
}
