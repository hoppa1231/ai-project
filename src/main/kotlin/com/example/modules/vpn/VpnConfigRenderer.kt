package com.example.modules.vpn

import com.example.common.Hashing
import com.example.db.NodeEntity
import com.example.db.PolicyEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

object VpnConfigRenderer {
    private val json = Json { prettyPrint = false }

    fun render(
        node: NodeEntity,
        vlessUuid: UUID,
        flow: String?,
        policy: PolicyEntity
    ): RenderedVpnConfig {
        val alpnJoined = node.realityAlpn.joinToString(",")
        val flowParam = flow?.let { "&flow=${enc(it)}" } ?: ""
        val alpnParam = if (alpnJoined.isBlank()) "" else "&alpn=${enc(alpnJoined)}"

        val uri = buildString {
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

        val config = buildJsonObject {
            put("format", "sing-box")
            put("version", 1)
            put("outbound", buildJsonObject {
                put("type", "vless")
                put("server", node.publicAddress)
                put("server_port", node.publicPort)
                put("uuid", vlessUuid.toString())
                flow?.let { put("flow", it) }
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
            })
            put("policy", buildJsonObject {
                put("version", policy.version)
                put("hash", policy.hash)
                put("defaultRoute", policy.defaultRoute)
                putJsonArray("includeApps") { policy.includeApps.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("excludeApps") { policy.excludeApps.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("includeDomains") { policy.includeDomains.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("excludeDomains") { policy.excludeDomains.forEach { add(JsonPrimitive(it)) } }
            })
        }

        val configJson = json.encodeToString(config)
        return RenderedVpnConfig(uri, configJson, Hashing.sha256Hex(configJson))
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
