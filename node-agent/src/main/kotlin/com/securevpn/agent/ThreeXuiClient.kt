package com.securevpn.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference

class ThreeXuiClient(
    private val config: ThreeXuiConfig
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val sessionCookie = AtomicReference<String?>()
    private val baseUrl = config.baseUrl.trimEnd('/')

    fun status(): String {
        val response = request(path = "/panel/api/server/status", method = "GET", body = null)
        return response.ifBlank { "OK" }
    }

    fun addClient(inboundId: Int, client: XuiClientPayload) {
        val settings = json.encodeToString(XuiSettingsPayload(clients = listOf(client)))
        val body = json.encodeToString(AddClientRequest(id = inboundId, settings = settings))
        request(path = "/panel/api/inbounds/addClient", method = "POST", body = body)
    }

    fun deleteClientByEmail(inboundId: Int, email: String) {
        val encodedEmail = URLEncoder.encode(email, Charsets.UTF_8)
        request(path = "/panel/api/inbounds/$inboundId/delClientByEmail/$encodedEmail", method = "POST", body = "")
    }

    fun listClients(inboundId: Int? = null): List<XuiClientSnapshot> {
        val root = json.parseToJsonElement(request(path = "/panel/api/inbounds/list", method = "GET", body = null)).jsonObject
        val inbounds = root["obj"]?.jsonArrayOrNull().orEmpty()
        return inbounds.flatMap { inboundElement ->
            val inbound = inboundElement.jsonObject
            val id = inbound["id"]?.jsonPrimitive?.intOrNull ?: return@flatMap emptyList()
            if (inboundId != null && id != inboundId) return@flatMap emptyList()
            val remark = inbound["remark"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val tag = inbound["tag"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val settings = parseObjectString(inbound["settings"])
            val trafficByEmail = inbound["clientStats"]?.jsonArrayOrNull()
                .orEmpty()
                .mapNotNull { stat ->
                    val obj = stat.jsonObject
                    val email = obj["email"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    email to obj
                }
                .toMap()

            settings["clients"]?.jsonArrayOrNull().orEmpty().mapNotNull { clientElement ->
                val client = clientElement.jsonObject
                val email = client["email"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val stat = trafficByEmail[email]
                XuiClientSnapshot(
                    inboundId = id,
                    inboundRemark = remark,
                    inboundTag = tag,
                    email = email,
                    uuid = client["id"]?.jsonPrimitive?.contentOrNull ?: client["password"]?.jsonPrimitive?.contentOrNull,
                    flow = client["flow"]?.jsonPrimitive?.contentOrNull,
                    enable = client["enable"]?.jsonPrimitive?.booleanOrNull ?: stat?.get("enable")?.jsonPrimitive?.booleanOrNull ?: true,
                    totalBytes = client["totalGB"]?.jsonPrimitive?.longOrNull ?: stat?.get("total")?.jsonPrimitive?.longOrNull ?: 0L,
                    upBytes = stat?.get("up")?.jsonPrimitive?.longOrNull ?: 0L,
                    downBytes = stat?.get("down")?.jsonPrimitive?.longOrNull ?: 0L,
                    expiryTime = client["expiryTime"]?.jsonPrimitive?.longOrNull ?: stat?.get("expiryTime")?.jsonPrimitive?.longOrNull ?: 0L,
                    limitIp = client["limitIp"]?.jsonPrimitive?.intOrNull ?: 0,
                    subId = client["subId"]?.jsonPrimitive?.contentOrNull,
                    tgId = client["tgId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                )
            }
        }
    }

    fun updateClientTrafficLimit(inboundId: Int, email: String, totalBytes: Long) {
        require(totalBytes >= 0) { "totalBytes must be >= 0" }
        val inbound = findInbound(inboundId)
        val settings = parseObjectString(inbound["settings"])
        val clients = settings["clients"]?.jsonArrayOrNull().orEmpty()
        var clientId: String? = null
        var updatedClient: JsonObject? = null
        clients.forEach { clientElement ->
            val client = clientElement.jsonObject
            if (client["email"]?.jsonPrimitive?.contentOrNull == email) {
                clientId = client["id"]?.jsonPrimitive?.contentOrNull
                    ?: client["password"]?.jsonPrimitive?.contentOrNull
                    ?: email
                updatedClient = JsonObject(client + ("totalGB" to JsonPrimitive(totalBytes)))
            }
        }
        val resolvedClientId = clientId ?: throw IllegalArgumentException("Client not found: $email")
        val updatedSettings = JsonObject(settings + ("clients" to JsonArray(listOf(updatedClient!!))))
        val body = json.encodeToString(
            AddClientRequest(
                id = inboundId,
                settings = json.encodeToString(updatedSettings)
            )
        )
        val encodedClientId = URLEncoder.encode(resolvedClientId, Charsets.UTF_8)
        request(path = "/panel/api/inbounds/updateClient/$encodedClientId", method = "POST", body = body)
    }

    private fun findInbound(inboundId: Int): JsonObject {
        val root = json.parseToJsonElement(request(path = "/panel/api/inbounds/list", method = "GET", body = null)).jsonObject
        return root["obj"]?.jsonArrayOrNull()
            .orEmpty()
            .map { it.jsonObject }
            .firstOrNull { it["id"]?.jsonPrimitive?.intOrNull == inboundId }
            ?: throw IllegalArgumentException("Inbound not found: $inboundId")
    }

    private fun parseObjectString(element: JsonElement?): JsonObject {
        val raw = element?.jsonPrimitive?.contentOrNull ?: return JsonObject(emptyMap())
        return json.parseToJsonElement(raw).jsonObject
    }

    private fun login(): String {
        val form = buildList {
            add("username=${URLEncoder.encode(config.username, Charsets.UTF_8)}")
            add("password=${URLEncoder.encode(config.password, Charsets.UTF_8)}")
            config.twoFactorCode?.let { add("twoFactorCode=${URLEncoder.encode(it, Charsets.UTF_8)}") }
        }.joinToString("&")

        val connection = open(path = "/login", method = "POST").apply {
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            doOutput = true
            outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        }

        try {
            val code = connection.responseCode
            val responseBody = readBody(connection, code)
            if (code !in 200..299) {
                throw IOException("3x-ui login failed HTTP $code: ${responseBody.take(300)}")
            }
            val cookie = connection.headerFields["Set-Cookie"]
                ?.firstOrNull { it.contains('=') }
                ?.substringBefore(';')
                ?: throw IOException("3x-ui login did not return session cookie")
            sessionCookie.set(cookie)
            return cookie
        } finally {
            connection.disconnect()
        }
    }

    private fun request(path: String, method: String, body: String?): String {
        val firstCookie = sessionCookie.get() ?: login()
        return runCatching {
            requestWithCookie(path, method, body, firstCookie)
        }.recoverCatching { firstError ->
            if (firstError is UnauthorizedXuiException) {
                requestWithCookie(path, method, body, login())
            } else {
                throw firstError
            }
        }.getOrThrow()
    }

    private fun requestWithCookie(path: String, method: String, body: String?, cookie: String): String {
        val connection = open(path = path, method = method).apply {
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }

        try {
            val code = connection.responseCode
            val responseBody = readBody(connection, code)
            if (code == 401 || code == 403) {
                throw UnauthorizedXuiException()
            }
            if (code !in 200..299) {
                throw IOException("3x-ui HTTP $code: ${responseBody.take(300)}")
            }
            return responseBody
        } finally {
            connection.disconnect()
        }
    }

    private fun open(path: String, method: String): HttpURLConnection {
        val url = URI("$baseUrl$path").toURL()
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 10_000
        }
    }

    private fun readBody(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty().trim()
    }
}

private fun JsonElement?.jsonArrayOrNull(): JsonArray? = this as? JsonArray

@Serializable
data class XuiClientPayload(
    val id: String,
    val email: String,
    val flow: String = "",
    val limitIp: Int = 0,
    val totalGB: Long = 0,
    val expiryTime: Long = 0,
    val enable: Boolean = true,
    val tgId: String = "",
    val subId: String = stableSubId(email),
    val reset: Int = 0
)

@Serializable
data class XuiClientSnapshot(
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

@Serializable
private data class XuiSettingsPayload(
    val clients: List<XuiClientPayload>
)

@Serializable
private data class AddClientRequest(
    val id: Int,
    val settings: String
)

private class UnauthorizedXuiException : RuntimeException()

private fun stableSubId(email: String): String {
    return email.lowercase()
        .filter { it.isLetterOrDigit() }
        .take(16)
        .padEnd(8, '0')
}
