package com.example.agent

import com.example.db.NodeEntity
import com.example.xray.XrayAdminClient
import com.example.xray.XrayUser
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
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
                    expiresAt = user.expiresAt.toString()
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
    val expiresAt: String
)

@Serializable
private data class AgentRemoveUserRequest(
    val inboundTag: String,
    val email: String
)
