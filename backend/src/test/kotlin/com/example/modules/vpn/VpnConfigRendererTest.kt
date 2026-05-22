package com.example.modules.vpn

import com.example.db.NodeEntity
import com.example.db.PolicyEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.time.Instant
import java.util.UUID

class VpnConfigRendererTest {
    @Test
    fun renderCascadeBuildsEntryToExitDetourChain() {
        val entryNode = node(
            id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            name = "Entry",
            address = "203.0.113.10"
        )
        val exitNode = node(
            id = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            name = "Exit",
            address = "198.51.100.20"
        )

        val rendered = VpnConfigRenderer.renderCascade(
            hops = listOf(
                VpnRouteHop(
                    index = 0,
                    role = "ENTRY",
                    node = entryNode,
                    vlessUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                    flow = "xtls-rprx-vision"
                ),
                VpnRouteHop(
                    index = 1,
                    role = "EXIT",
                    node = exitNode,
                    vlessUuid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                    flow = "xtls-rprx-vision"
                )
            ),
            policy = policy()
        )

        val config = Json.parseToJsonElement(rendered.configJson).jsonObject
        val outbounds = config.getValue("outbounds").jsonArray.map { it.jsonObject }

        assertEquals("CASCADE", config.getValue("routeMode").jsonPrimitive.content)
        assertEquals("exit-1", config.getValue("route").jsonObject.getValue("final").jsonPrimitive.content)
        assertEquals("entry-0", outbounds[0].getValue("tag").jsonPrimitive.content)
        assertEquals("exit-1", outbounds[1].getValue("tag").jsonPrimitive.content)
        assertEquals("entry-0", outbounds[1].getValue("detour").jsonPrimitive.content)
        assertTrue(rendered.vlessUri.contains("203.0.113.10:443"))
    }

    private fun node(id: UUID, name: String, address: String): NodeEntity =
        NodeEntity(
            id = id,
            name = name,
            region = "ru",
            countryCode = "RU",
            hostname = "$name.example.test",
            publicAddress = address,
            publicPort = 443,
            apiHost = "127.0.0.1",
            apiPort = 9000,
            inboundTag = "inbound",
            realityServerName = "www.example.com",
            realityPublicKey = "public-key",
            realityShortId = "abcd1234",
            realityFingerprint = "chrome",
            realityAlpn = listOf("h2", "http/1.1"),
            status = "ACTIVE",
            health = "HEALTHY",
            weight = 100,
            load = 0,
            maxClients = 1000
        )

    private fun policy(): PolicyEntity =
        PolicyEntity(
            userId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
            version = 1,
            defaultRoute = "VPN",
            includeApps = emptyList(),
            excludeApps = emptyList(),
            includeDomains = emptyList(),
            excludeDomains = emptyList(),
            hash = "hash",
            updatedAt = Instant.EPOCH
        )
}
