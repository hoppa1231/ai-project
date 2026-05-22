package com.securevpn.app.vpn

import com.securevpn.app.data.RouteRule
import com.securevpn.app.data.RoutingPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigFactoryTest {
    @Test
    fun serverCascadeConfigKeepsDetourChainAndRoutesVpnRulesToFinalOutbound() {
        val config = JSONObject(
            SingBoxConfigFactory.fromServerClientConfig(
                clientConfigJson = cascadeClientConfig,
                policy = RoutingPolicy(
                    version = 1,
                    defaultRoute = "VPN",
                    routeRules = listOf(
                        RouteRule(
                            id = "rule-1",
                            source = "USER",
                            defaultRuleKey = null,
                            name = "VPN domain",
                            description = "",
                            enabled = true,
                            priority = 100,
                            matchType = "DOMAIN_SUFFIX",
                            values = listOf("blocked.example"),
                            action = "VPN"
                        )
                    )
                )
            )
        )

        val outbounds = config.getJSONArray("outbounds")
        val entry = outbounds.getJSONObject(0)
        val exit = outbounds.getJSONObject(1)
        val route = config.getJSONObject("route")
        val routeRules = route.getJSONArray("rules")
        val userRule = routeRules.getJSONObject(routeRules.length() - 1)

        assertEquals("entry-0", entry.getString("tag"))
        assertEquals("exit-1", exit.getString("tag"))
        assertEquals("entry-0", exit.getString("detour"))
        assertEquals("exit-1", route.getString("final"))
        assertEquals("exit-1", userRule.getString("outbound"))
        assertTrue(config.getJSONArray("inbounds").getJSONObject(0).has("route_exclude_address"))
    }

    @Test
    fun directPolicyUsesDirectFinalButRemoteDnsStillUsesCascadeFinal() {
        val config = JSONObject(
            SingBoxConfigFactory.fromServerClientConfig(
                clientConfigJson = cascadeClientConfig,
                policy = RoutingPolicy.default().copy(defaultRoute = "DIRECT")
            )
        )

        val route = config.getJSONObject("route")
        val dnsServers = config.getJSONObject("dns").getJSONArray("servers")
        val remoteDns = dnsServers.getJSONObject(1)

        assertEquals("direct", route.getString("final"))
        assertEquals("exit-1", remoteDns.getString("detour"))
    }

    private val cascadeClientConfig = """
        {
          "format": "sing-box",
          "version": 1,
          "routeMode": "CASCADE",
          "outbounds": [
            {
              "tag": "entry-0",
              "type": "vless",
              "server": "203.0.113.10",
              "server_port": 443,
              "uuid": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              "flow": "xtls-rprx-vision",
              "tls": {
                "enabled": true,
                "server_name": "www.example.com",
                "utls": { "enabled": true, "fingerprint": "chrome" },
                "reality": { "enabled": true, "public_key": "public-key", "short_id": "abcd1234" }
              }
            },
            {
              "tag": "exit-1",
              "type": "vless",
              "server": "198.51.100.20",
              "server_port": 443,
              "uuid": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
              "flow": "xtls-rprx-vision",
              "detour": "entry-0",
              "tls": {
                "enabled": true,
                "server_name": "www.example.com",
                "utls": { "enabled": true, "fingerprint": "chrome" },
                "reality": { "enabled": true, "public_key": "public-key", "short_id": "efgh5678" }
              }
            }
          ],
          "route": { "final": "exit-1" }
        }
    """.trimIndent()
}
