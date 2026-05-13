package com.example.agent

import com.example.db.NodeEntity
import com.example.xray.XrayAdminClient
import com.example.xray.XrayNodeClient
import com.example.xray.XrayUser
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import kotlin.system.measureTimeMillis

class AgentXrayAdminClient(
    private val scheme: String,
    basePath: String,
    private val token: String
) : XrayAdminClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val normalizedBasePath = basePath.trim().trimEnd('/')

    override fun addUser(node: NodeEntity, user: XrayUser) {
        request(
            node = node,
            path = "/v1/users/add",
            method = "POST",
            body = json.encodeToString(
                AgentAddUserRequest(
                    inboundTag = node.inboundTag,
                    email = user.email,
                    uuid = user.uuid.toString(),
                    flow = user.flow,
                    expiresAt = user.expiresAt.toString(),
                    tgId = user.telegramId?.toString()
                )
            )
        )
    }

    override fun removeUser(node: NodeEntity, email: String) {
        request(
            node = node,
            path = "/v1/users/remove",
            method = "POST",
            body = json.encodeToString(
                AgentRemoveUserRequest(
                    inboundTag = node.inboundTag,
                    email = email
                )
            )
        )
    }

    override fun ping(node: NodeEntity): Int {
        val elapsed = measureTimeMillis {
            request(node = node, path = "/v1/health", method = "GET", body = null)
        }
        return elapsed.toInt().coerceAtLeast(1)
    }

    override fun listClients(node: NodeEntity): List<XrayNodeClient> {
        val inboundTag = URLEncoder.encode(node.inboundTag, Charsets.UTF_8)
        val response = request(node = node, path = "/v1/clients?inboundTag=$inboundTag", method = "GET", body = null)
        return json.decodeFromString(AgentClientsResponse.serializer(), response).clients.map {
            XrayNodeClient(
                inboundId = it.inboundId,
                inboundRemark = it.inboundRemark,
                inboundTag = it.inboundTag,
                email = it.email,
                uuid = it.uuid,
                flow = it.flow,
                enabled = it.enable,
                totalBytes = it.totalBytes,
                upBytes = it.upBytes,
                downBytes = it.downBytes,
                expiryTime = it.expiryTime,
                limitIp = it.limitIp,
                subId = it.subId,
                telegramId = it.tgId?.toLongOrNull()
            )
        }
    }

    override fun updateClientTrafficLimit(node: NodeEntity, email: String, totalBytes: Long) {
        request(
            node = node,
            path = "/v1/users/update",
            method = "POST",
            body = json.encodeToString(
                AgentUpdateUserRequest(
                    inboundTag = node.inboundTag,
                    email = email,
                    totalBytes = totalBytes
                )
            )
        )
    }

    private fun request(node: NodeEntity, path: String, method: String, body: String?): String {
        val url = URI("$scheme://${node.apiHost}:${node.apiPort}$normalizedBasePath$path").toURL()
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }

        try {
            val code = connection.responseCode
            val responseBody = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                throw IOException("agent ${node.name} returned HTTP $code: ${responseBody.take(300)}")
            }
            return responseBody
        } finally {
            connection.disconnect()
        }
    }
}

@Serializable
private data class AgentAddUserRequest(
    val inboundTag: String,
    val email: String,
    val uuid: String,
    val flow: String?,
    val expiresAt: String,
    val tgId: String? = null
)

@Serializable
private data class AgentRemoveUserRequest(
    val inboundTag: String,
    val email: String
)

@Serializable
private data class AgentUpdateUserRequest(
    val inboundTag: String,
    val email: String,
    val totalBytes: Long
)

@Serializable
private data class AgentClientsResponse(
    val clients: List<AgentClientSnapshot>
)

@Serializable
private data class AgentClientSnapshot(
    val inboundId: Int,
    val inboundRemark: String,
    val inboundTag: String,
    val email: String,
    val uuid: String?,
    val flow: String?,
    val enable: Boolean,
    val totalBytes: Long,
    val upBytes: Long,
    val downBytes: Long,
    val expiryTime: Long,
    val limitIp: Int,
    val subId: String?,
    val tgId: String? = null
)
