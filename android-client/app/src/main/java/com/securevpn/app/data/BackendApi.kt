package com.securevpn.app.data

import android.content.Context
import android.content.SharedPreferences
import com.securevpn.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class BackendApi(
    context: Context,
    apiBaseUrl: String = BuildConfig.API_BASE_URL
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("securevpn_api", Context.MODE_PRIVATE)
    private val baseUrl = apiBaseUrl.trimEnd('/')

    suspend fun bootstrap(): VpnBootstrap {
        ensureSession()
        val nodes = loadNodes()
        val quota = runCatching { loadQuota() }.getOrNull()
        return VpnBootstrap(nodes = nodes, quota = quota, activeConfigId = activeConfigId)
    }

    suspend fun issueVpnConfig(region: String? = null, exitNodeId: String? = null): IssuedConfig {
        val session = ensureSession()
        val body = JSONObject()
            .put("deviceId", session.deviceId)
            .put("routeMode", "CASCADE")
            .apply {
                if (!region.isNullOrBlank()) put("region", region)
                if (!exitNodeId.isNullOrBlank()) put("exitNodeId", exitNodeId)
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
            val json = JSONObject(response)
            val result = RevokeResult(
                configId = json.optString("configId", configId),
                status = json.optString("status", "REVOKED"),
                revokedAt = json.optStringOrNull("revokedAt")
            )
            if (result.status in locallyInactiveRevokeStatuses) {
                clearActiveConfig()
            }
            result
        } catch (e: HttpException) {
            if (e.code == 400 || e.code == 404) {
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
                        load = item.optInt("load", item.optDouble("loadScore", 0.0).toInt())
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
            freeGb = json.optGb("freeGb", "freeBytes"),
            purchasedGb = json.optGb("purchasedGb", "purchasedBytes"),
            usedGb = json.optGb("usedGb", "usedBytes"),
            remainingGb = json.optGb("remainingGb", "remainingBytes")
        )
    }

    private suspend fun authorizedRequest(
        path: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        allowSessionRecreate: Boolean = method == "GET"
    ): String {
        val session = ensureSession()
        return try {
            requestText(path = path, method = method, bearerToken = session.accessToken, body = body, headers = headers)
        } catch (e: HttpException) {
            if (e.code != 401) throw e
            val refreshed = recoverSessionAfterUnauthorized(allowSessionRecreate)
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
            return recoverSessionAfterUnauthorized(allowSessionRecreate = true)
        }
        return createGuestSession()
    }

    private suspend fun recoverSessionAfterUnauthorized(allowSessionRecreate: Boolean): AuthSession {
        return runCatching { refreshSession() }.getOrElse { error ->
            if (!allowSessionRecreate) throw error
            if (error is HttpException && error.code != 401) throw error
            clearSession()
            createGuestSession()
        }
    }

    private suspend fun createGuestSession(): AuthSession {
        val fingerprint = getOrCreateDeviceFingerprint()
        val body = JSONObject()
            .put("deviceFingerprint", fingerprint)
            .put("deviceName", android.os.Build.MODEL ?: "Android")
            .put("platform", "android")
            .put("appVersion", BuildConfig.VERSION_NAME)
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
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SecureVPN-Android/${BuildConfig.VERSION_NAME}")
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
        private val locallyInactiveRevokeStatuses = setOf("REVOKED", "EXPIRED", "FAILED")

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

private class HttpException(val code: Int, body: String) : RuntimeException(formatHttpMessage(code, body))

private fun formatHttpMessage(code: Int, body: String): String {
    if (body.isBlank()) return "HTTP $code: empty response"
    val apiMessage = runCatching {
        val json = JSONObject(body)
        val errorCode = json.optString("code").takeIf { it.isNotBlank() }
        val message = json.optString("message").takeIf { it.isNotBlank() }
        listOfNotNull(errorCode, message).joinToString(": ").takeIf { it.isNotBlank() }
    }.getOrNull()
    return "HTTP $code: ${apiMessage ?: body}"
}

private fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() }
}

private fun JSONObject.optGb(gbName: String, bytesName: String): Double {
    if (has(gbName) && !isNull(gbName)) return optDouble(gbName, 0.0)
    val bytes = optLong(bytesName, 0L)
    return bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
}
