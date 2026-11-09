package com.example.db.repo

import com.example.db.RoutingRuleEntity
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolicyRepositoryTest {
    private fun rule(key: String?, enabled: Boolean = true) = RoutingRuleEntity(
        UUID.randomUUID(), if (key == null) "USER" else "DEFAULT", null, key,
        key ?: "Custom", "", 500, enabled, "DOMAIN_SUFFIX", listOf("example.com"), "VPN", true, 1
    )

    @Test fun builtInVpnServicesIgnoreDisabledLegacyOverride() {
        val base = rule("vpn-services")
        val rules = mandatoryServiceRules(listOf(base), listOf(base.copy(enabled = false, name = "Legacy")))
        assertEquals(1, rules.size)
        assertEquals("vpn-services", rules.single().name)
        assertTrue(rules.single().enabled)
        assertFalse(rules.single().editable)
    }

    @Test fun disabledCustomRuleIsPreservedWhileBaseIsEnabled() {
        val custom = rule(null, enabled = false)
        val rules = mandatoryServiceRules(listOf(rule("vpn-services", enabled = false)), listOf(custom))
        assertEquals(custom, rules.single { it.source == "USER" })
        assertTrue(rules.single { it.source == "DEFAULT" }.enabled)
    }
}
