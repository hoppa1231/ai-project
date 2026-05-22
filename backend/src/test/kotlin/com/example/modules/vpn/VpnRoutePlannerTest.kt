package com.example.modules.vpn

import com.example.common.ApiException
import com.example.db.NodeEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import java.util.UUID

class VpnRoutePlannerTest {
    @Test
    fun cascadePlanUsesEntryAndExitWhenEntryNodeExists() {
        val entry = node("11111111-1111-1111-1111-111111111111", "Entry")
        val exit = node("22222222-2222-2222-2222-222222222222", "Exit")

        val plan = planRoute(
            requestedRouteMode = "CASCADE",
            exitNode = exit,
            cascadeFallbackToSingle = true,
            pickEntryNode = { entry }
        )

        assertEquals("CASCADE", plan.routeMode)
        assertNull(plan.fallbackReason)
        assertEquals(listOf("ENTRY", "EXIT"), plan.hops.map { it.role })
        assertEquals(listOf(entry.id, exit.id), plan.hops.map { it.node.id })
    }

    @Test
    fun cascadePlanFallsBackToSingleWhenEntryMissingAndFallbackEnabled() {
        val exit = node("22222222-2222-2222-2222-222222222222", "Exit")

        val plan = planRoute(
            requestedRouteMode = "CASCADE",
            exitNode = exit,
            cascadeFallbackToSingle = true,
            pickEntryNode = { null }
        )

        assertEquals("SINGLE", plan.routeMode)
        assertEquals("NO_ENTRY_NODES", plan.fallbackReason)
        assertEquals(listOf("EXIT"), plan.hops.map { it.role })
        assertEquals(exit.id, plan.hops.single().node.id)
    }

    @Test
    fun cascadePlanFailsWhenEntryMissingAndFallbackDisabled() {
        val exit = node("22222222-2222-2222-2222-222222222222", "Exit")

        val error = assertFailsWith<ApiException> {
            planRoute(
                requestedRouteMode = "CASCADE",
                exitNode = exit,
                cascadeFallbackToSingle = false,
                pickEntryNode = { null }
            )
        }

        assertEquals("NO_ENTRY_NODES", error.code)
    }

    @Test
    fun singlePlanDoesNotLookForEntryNode() {
        val exit = node("22222222-2222-2222-2222-222222222222", "Exit")
        var entryLookupCount = 0

        val plan = planRoute(
            requestedRouteMode = "SINGLE",
            exitNode = exit,
            cascadeFallbackToSingle = true,
            pickEntryNode = {
                entryLookupCount += 1
                null
            }
        )

        assertEquals("SINGLE", plan.routeMode)
        assertEquals(0, entryLookupCount)
        assertEquals(listOf("EXIT"), plan.hops.map { it.role })
    }

    private fun node(id: String, name: String): NodeEntity =
        NodeEntity(
            id = UUID.fromString(id),
            name = name,
            region = "ru",
            countryCode = "RU",
            hostname = "$name.example.test",
            publicAddress = "203.0.113.${id.first()}",
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
}
