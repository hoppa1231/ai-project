package app.chspch.client

import app.chspch.client.data.VpnNode
import org.junit.Assert.*
import org.junit.Test

class NodeAvailabilityTest {
    @Test fun unavailableNodesRemainVisibleButCannotBeUsed() {
        for (health in listOf("UNHEALTHY", "DEGRADED", "UNKNOWN")) {
            val node = VpnNode("fin", "Finland", "Finland", "FI", health, 0).toServerNode(0)
            assertEquals("fin", node.id)
            assertFalse(node.available)
            assertFalse(node.recommended)
        }
    }

    @Test fun healthyAlternativeCanBeSelected() {
        val node = VpnNode("fra", "Франция", "France", "FR", "HEALTHY", 0).toServerNode(0)
        assertTrue(node.available)
        assertTrue(node.recommended)
    }
}
