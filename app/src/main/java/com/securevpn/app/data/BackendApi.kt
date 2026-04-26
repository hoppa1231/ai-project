package com.securevpn.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object BackendApi {
    private const val BASE_URL = "https://tech-supp-test.ru"
    private const val DEVICE_FINGERPRINT = "android-dev-device"

    private var accessToken: String? = null
    private var deviceId: String? = null

    suspend fun getVpnNodesText(): String {
        val session = ensureGuestSession()
        return requestText(path = "/nodes", bearerToken = session.accessToken)
    }

    suspend fun issueVpnConfigText(): String {
        val session = ensureGuestSession()
        return requestText(
            path = "/vpn/issue",
            method = "POST",
            bearerToken = session.accessToken,
            body = """{"deviceId":"${session.deviceId}"}"""
        )
    }

    private suspend fun ensureGuestSession(): GuestSession {
        val cachedAccessToken = accessToken
        val cachedDeviceId = deviceId
        if (cachedAccessToken != null && cachedDeviceId != null) {
            return GuestSession(cachedAccessToken, cachedDeviceId)
        }

        val body = """
            {
              "deviceFingerprint": "$DEVICE_FINGERPRINT",
              "deviceName": "Android Dev",
              "platform": "android",
              "appVersion": "0.1"
            }
        """.trimIndent()
        val response = requestText(path = "/auth/guest", method = "POST", body = body)
        val newAccessToken = response.extractJsonString("accessToken")
        val newDeviceId = response.extractJsonString("deviceId")

        accessToken = newAccessToken
        deviceId = newDeviceId

        return GuestSession(newAccessToken, newDeviceId)
    }

    private suspend fun requestText(
        path: String,
        method: String = "GET",
        bearerToken: String? = null,
        body: String? = null
    ): String = withContext(Dispatchers.IO) {
        val connection = (URL("$BASE_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json, text/plain")
            if (bearerToken != null) {
                setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }

        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                error("HTTP $code: $body")
            }

            body.trim()
        } finally {
            connection.disconnect()
        }
    }

    private data class GuestSession(val accessToken: String, val deviceId: String)

    private fun String.extractJsonString(field: String): String {
        val pattern = Regex(""""$field"\s*:\s*"([^"]+)"""")
        return pattern.find(this)?.groupValues?.get(1)
            ?: error("Missing '$field' in API response")
    }
}
