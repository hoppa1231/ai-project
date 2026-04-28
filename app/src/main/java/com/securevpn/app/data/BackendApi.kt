package com.securevpn.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class BackendApi(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("securevpn_api", Context.MODE_PRIVATE)

    suspend fun bootstrap(): VpnBootstrap {
        ensureSession()
        val nodes = loadNodes()
        val quota = runCatching { loadQuota() }.getOrNull()
        return VpnBootstrap(nodes = nodes, quota = quota, activeConfigId = activeConfigId)
    }

    suspend fun issueVpnConfig(region: String? = null): IssuedConfig {
        val session = ensureSession()
        val body = JSONObject()
            .put("deviceId", session.deviceId)
            .apply {
                if (!region.isNullOrBlank()) put("region", region)
            }
            .toString()

        val response = authorizedRequest(
            path = "/vpn/issue",
            method = "POST",
            body = body,
            headers = mapOf("Idempotency-Key" to UUID.randomUUID().toString())
        )

        val issued = parseIssuedConfig(response)
        prefs.edit()
            .putString(KEY_ACTIVE_CONFIG_ID, issued.configId)
            .putString(KEY_ACTIVE_VLESS_URI, issued.vlessUri)
            .apply()
        return issued
    }

    suspend fun revokeActiveConfig(): RevokeResult {
        val configId = activeConfigId ?: return RevokeResult(configId = "", status = "NO_ACTIVE_CONFIG")
        val body = JSONObject()
            .put("configId", configId)
            .put("reason", "user_request")
            .toString()

        return try {
            val response = authorizedRequest(path = "/vpn/revoke", method = "POST", body = body)
            clearActiveConfig()
            val json = JSONObject(response)
            RevokeResult(
                configId = json.optString("configId", configId),
                status = json.optString("status", "REVOKED"),
                revokedAt = json.optStringOrNull("revokedAt")
            )
        } catch (e: HttpException) {
            if (e.code == 404) {
                clearActiveConfig()
                RevokeResult(configId = configId, status = "CLEARED_LOCALLY")
            } else {
                throw e
            }
        }
    }

    fun hasActiveConfig(): Boolean = !activeConfigId.isNullOrBlank()

    private suspend fun loadNodes(): List<VpnNode> {
        val response = authorizedRequest(path = "/nodes")
        val nodes = JSONArray(response)
        return buildList {
            for (index in 0 until nodes.length()) {
                val item = nodes.getJSONObject(index)
                add(
                    VpnNode(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        region = item.getString("region"),
                        countryCode = item.optString("countryCode", "UN"),
                        health = item.optString("health", "UNKNOWN"),
                        load = item.optInt("load", 0)
                    )
                )
            }
        }
    }

    private suspend fun loadQuota(): QuotaStatus {
        val response = authorizedRequest(path = "/quota/current")
        val json = JSONObject(response)
        return QuotaStatus(
            accountType = json.optString("accountType", "GUEST"),
            freeGb = json.optDouble("freeGb", 0.0),
            purchasedGb = json.optDouble("purchasedGb", 0.0),
            usedGb = json.optDouble("usedGb", 0.0),
            remainingGb = json.optDouble("remainingGb", 0.0)
        )
    }

    private suspend fun authorizedRequest(
        path: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): String {
        val session = ensureSession()
        return try {
            requestText(path = path, method = method, bearerToken = session.accessToken, body = body, headers = headers)
        } catch (e: HttpException) {
            if (e.code != 401) throw e
            val refreshed = refreshSession()
            requestText(path = path, method = method, bearerToken = refreshed.accessToken, body = body, headers = headers)
        }
    }

    private suspend fun ensureSession(): AuthSession {
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
        val deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (!accessToken.isNullOrBlank() && !deviceId.isNullOrBlank()) {
            return AuthSession(accessToken = accessToken, refreshToken = refreshToken, deviceId = deviceId)
        }
        if (!refreshToken.isNullOrBlank()) {
            return runCatching { refreshSession() }.getOrElse {
                clearSession()
                createGuestSession()
            }
        }
        return createGuestSession()
    }

    private suspend fun createGuestSession(): AuthSession {
        val fingerprint = getOrCreateDeviceFingerprint()
        val body = JSONObject()
            .put("deviceFingerprint", fingerprint)
            .put("deviceName", android.os.Build.MODEL ?: "Android")
            .put("platform", "android")
            .put("appVersion", "0.1")
            .toString()

        val response = requestText(path = "/auth/guest", method = "POST", body = body)
        return storeSession(JSONObject(response))
    }

    private suspend fun refreshSession(): AuthSession {
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
            ?: throw IllegalStateException("Refresh token is missing")
        val body = JSONObject().put("refreshToken", refreshToken).toString()
        val response = requestText(path = "/auth/refresh", method = "POST", body = body)
        return storeSession(JSONObject(response))
    }

    private fun storeSession(json: JSONObject): AuthSession {
        val accessToken = json.getString("accessToken")
        val refreshToken = json.optStringOrNull("refreshToken")
        val deviceId = json.getString("deviceId")
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_DEVICE_ID, deviceId)
            .apply()
        return AuthSession(accessToken = accessToken, refreshToken = refreshToken, deviceId = deviceId)
    }

    private suspend fun requestText(
        path: String,
        method: String = "GET",
        bearerToken: String? = null,
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        val connection = (URL("$BASE_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SecureVPN-Android/0.1")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
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
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty().trim()
            if (code !in 200..299) {
                throw HttpException(code = code, body = responseBody)
            }
            responseBody
        } finally {
            connection.disconnect()
        }
    }

    private fun parseIssuedConfig(response: String): IssuedConfig {
        val json = JSONObject(response)
        val node = json.getJSONObject("node")
        return IssuedConfig(
            configId = json.getString("configId"),
            status = json.optString("status", "ISSUED"),
            expiresAt = json.optString("expiresAt", ""),
            nodeName = node.optString("name", "VPN node"),
            region = node.optString("region", ""),
            vlessUri = json.optStringOrNull("vlessUri")
        )
    }

    private fun getOrCreateDeviceFingerprint(): String {
        val existing = prefs.getString(KEY_DEVICE_FINGERPRINT, null)
        if (!existing.isNullOrBlank()) return existing
        val created = "android-${UUID.randomUUID()}"
        prefs.edit().putString(KEY_DEVICE_FINGERPRINT, created).apply()
        return created
    }

    private fun clearActiveConfig() {
        prefs.edit()
            .remove(KEY_ACTIVE_CONFIG_ID)
            .remove(KEY_ACTIVE_VLESS_URI)
            .apply()
    }

    private fun clearSession() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_ACTIVE_CONFIG_ID)
            .remove(KEY_ACTIVE_VLESS_URI)
            .apply()
    }

    private val activeConfigId: String?
        get() = prefs.getString(KEY_ACTIVE_CONFIG_ID, null)

    companion object {
        private const val BASE_URL = "https://tech-supp-test.ru"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_FINGERPRINT = "device_fingerprint"
        private const val KEY_ACTIVE_CONFIG_ID = "active_config_id"
        private const val KEY_ACTIVE_VLESS_URI = "active_vless_uri"
    }
}

data class VpnBootstrap(
    val nodes: List<VpnNode>,
    val quota: QuotaStatus?,
    val activeConfigId: String?
)

data class VpnNode(
    val id: String,
    val name: String,
    val region: String,
    val countryCode: String,
    val health: String,
    val load: Int
)

data class QuotaStatus(
    val accountType: String,
    val freeGb: Double,
    val purchasedGb: Double,
    val usedGb: Double,
    val remainingGb: Double
) {
    val totalGb: Double get() = freeGb + purchasedGb
    val usedPercent: Float get() = if (totalGb <= 0.0) 0f else (usedGb / totalGb).toFloat().coerceIn(0f, 1f)
}

data class IssuedConfig(
    val configId: String,
    val status: String,
    val expiresAt: String,
    val nodeName: String,
    val region: String,
    val vlessUri: String?
)

data class RevokeResult(
    val configId: String,
    val status: String,
    val revokedAt: String? = null
)

private data class AuthSession(
    val accessToken: String,
    val refreshToken: String?,
    val deviceId: String
)

private class HttpException(val code: Int, body: String) : RuntimeException("HTTP $code: ${body.ifBlank { "empty response" }}")

private fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() }
}
