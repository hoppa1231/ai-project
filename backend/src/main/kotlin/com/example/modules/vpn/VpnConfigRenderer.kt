package com.example.modules.vpn

import com.example.common.Hashing
import com.example.db.NodeEntity
import com.example.db.PolicyEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

data class RenderedVpnConfig(
    val vlessUri: String,
    val configJson: String,
    val hash: String
)

data class VpnRouteHop(
    val index: Int,
    val role: String,
    val node: NodeEntity,
    val vlessUuid: UUID,
    val flow: String?
)

object VpnConfigRenderer {
    private val json = Json { prettyPrint = false }

    fun render(
        node: NodeEntity,
        vlessUuid: UUID,
        flow: String?,
        policy: PolicyEntity
    ): RenderedVpnConfig {
        val uri = vlessUri(node, vlessUuid, flow)

        val config = buildJsonObject {
            put("format", "sing-box")
            put("version", 1)
            put("routeMode", "SINGLE")
            put("outbound", outboundJson("exit-0", node, vlessUuid, flow))
            putJsonArray("outbounds") {
                add(outboundJson("exit-0", node, vlessUuid, flow))
            }
            put("route", buildJsonObject {
                put("final", "exit-0")
            })
            put("policy", policyJson(policy))
        }

        val configJson = json.encodeToString(config)
        return RenderedVpnConfig(uri, configJson, Hashing.sha256Hex(configJson))
    }

    fun renderCascade(
        hops: List<VpnRouteHop>,
        policy: PolicyEntity
    ): RenderedVpnConfig {
        require(hops.size >= 2) { "cascade route requires at least entry and exit hops" }
        val sorted = hops.sortedBy { it.index }
        val entry = sorted.first()
        val exit = sorted.last()
        val uri = vlessUri(entry.node, entry.vlessUuid, entry.flow)

        val config = buildJsonObject {
            put("format", "sing-box")
            put("version", 1)
            put("routeMode", "CASCADE")
            put("entryNode", nodeJson(entry.node))
            put("exitNode", nodeJson(exit.node))
            putJsonArray("hops") {
                sorted.forEach { hop ->
                    add(buildJsonObject {
                        put("index", hop.index)
                        put("role", hop.role)
                        put("tag", hop.tag)
                        put("node", nodeJson(hop.node))
                        put("vlessUri", vlessUri(hop.node, hop.vlessUuid, hop.flow))
                    })
                }
            }
            putJsonArray("outbounds") {
                sorted.forEachIndexed { position, hop ->
                    val detourTag = sorted.getOrNull(position - 1)?.tag
                    add(outboundJson(hop.tag, hop.node, hop.vlessUuid, hop.flow, detourTag))
                }
            }
            put("route", buildJsonObject {
                put("final", exit.tag)
            })
            put("policy", policyJson(policy))
        }

        val configJson = json.encodeToString(config)
        return RenderedVpnConfig(uri, configJson, Hashing.sha256Hex(configJson))
    }

    fun vlessUri(node: NodeEntity, vlessUuid: UUID, flow: String?): String {
        val alpnJoined = node.realityAlpn.joinToString(",")
        val flowParam = flow?.let { "&flow=${enc(it)}" } ?: ""
        val alpnParam = if (alpnJoined.isBlank()) "" else "&alpn=${enc(alpnJoined)}"

        return buildString {
            append("vless://")
            append(vlessUuid)
            append("@")
            append(node.publicAddress)
            append(":")
            append(node.publicPort)
            append("?type=tcp&security=reality&encryption=none")
            append(flowParam)
            append("&sni=")
            append(enc(node.realityServerName))
            append("&fp=")
            append(enc(node.realityFingerprint))
            append("&pbk=")
            append(enc(node.realityPublicKey))
            append("&sid=")
            append(enc(node.realityShortId))
            append(alpnParam)
            append("#")
            append(enc(node.name))
        }
    }

    private val VpnRouteHop.tag: String
        get() = "${role.lowercase()}-$index"

    private fun outboundJson(
        tag: String,
        node: NodeEntity,
        vlessUuid: UUID,
        flow: String?,
        detourTag: String? = null
    ): JsonObject = buildJsonObject {
        put("tag", tag)
        put("type", "vless")
        put("server", node.publicAddress)
        put("server_port", node.publicPort)
        put("uuid", vlessUuid.toString())
        flow?.let { put("flow", it) }
        detourTag?.let { put("detour", it) }
        put("tls", buildJsonObject {
            put("enabled", true)
            put("server_name", node.realityServerName)
            put("utls", buildJsonObject {
                put("enabled", true)
                put("fingerprint", node.realityFingerprint)
            })
            put("reality", buildJsonObject {
                put("enabled", true)
                put("public_key", node.realityPublicKey)
                put("short_id", node.realityShortId)
            })
            putJsonArray("alpn") {
                node.realityAlpn.forEach { add(JsonPrimitive(it)) }
            }
        })
    }

    private fun nodeJson(node: NodeEntity): JsonObject = buildJsonObject {
        put("id", node.id.toString())
        put("name", node.name)
        put("region", node.region)
        put("countryCode", node.countryCode)
    }

    private fun policyJson(policy: PolicyEntity): JsonObject = buildJsonObject {
        put("version", policy.version)
        put("hash", policy.hash)
        put("defaultRoute", policy.defaultRoute)
        putJsonArray("includeApps") { policy.includeApps.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("excludeApps") { policy.excludeApps.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("includeDomains") { policy.includeDomains.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("excludeDomains") { policy.excludeDomains.forEach { add(JsonPrimitive(it)) } }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
