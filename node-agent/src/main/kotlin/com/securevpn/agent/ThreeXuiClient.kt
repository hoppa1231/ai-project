package com.securevpn.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
