package com.securevpn.app.data

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.securevpn.app.BuildConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("securevpn_api", Context.MODE_PRIVATE)
    private val baseUrl = apiBaseUrl.trimEnd('/')

    suspend fun bootstrap(): VpnBootstrap = coroutineScope {
        ensureSession()
        val clientSettings = async { runCatching { loadClientSettings() }.getOrDefault(ClientSettings.default()) }
        val nodes = async { loadNodes() }
        val quota = async { runCatching { loadQuota() }.getOrNull() }
        val notifications = async { runCatching { loadNotifications() }.getOrDefault(emptyList()) }
        VpnBootstrap(
            nodes = nodes.await(),
            quota = quota.await(),
            notifications = notifications.await(),
            clientSettings = clientSettings.await(),
            activeConfigId = activeConfigId
        )
    }

    suspend fun loadRoutingPolicy(): RoutingPolicy {
        val response = authorizedRequest(path = "/policy/current")
        return parseRoutingPolicy(response).also { cacheRoutingPolicy(response) }
    }

    fun cachedRoutingPolicy(): RoutingPolicy {
        val cached = prefs.getString(KEY_ROUTING_POLICY_JSON, null)
        return cached?.let { runCatching { parseRoutingPolicy(it) }.getOrNull() }
            ?: RoutingPolicy.default()
    }

    suspend fun loadCurrentQuota(): QuotaStatus = loadQuota()

    suspend fun loadCurrentNotifications(): List<ServerNotification> = loadNotifications()

    suspend fun markNotificationRead(notificationId: String) {
        rememberReadNotification(notificationId)
        authorizedRequest(path = "/notifications/$notificationId/read", method = "POST", body = "{}")
    }

    suspend fun updateRoutingPolicy(policy: RoutingPolicy): RoutingPolicy {
        val rules = JSONArray()
        policy.routeRules.forEach { rule ->
            rules.put(
                JSONObject()
                    .apply {
                        if (rule.source == "USER" && rule.id.isNotBlank()) put("id", rule.id)
                        rule.defaultRuleKey?.let { put("defaultRuleKey", it) }
                    }
                    .put("name", rule.name)
                    .put("enabled", rule.enabled)
                    .put("priority", rule.priority)
                    .put("matchType", rule.matchType)
                    .put("values", JSONArray(rule.values))
                    .put("action", rule.action)
            )
        }
        val body = JSONObject()
            .put("ifVersion", policy.version)
            .put("defaultRoute", policy.defaultRoute)
            .put("includeApps", JSONArray())
            .put("excludeApps", JSONArray())
            .put("includeDomains", JSONArray())
            .put("excludeDomains", JSONArray())
            .put("routeRules", rules)
            .toString()

        val response = authorizedRequest(path = "/policy/current", method = "PUT", body = body)
        return parseRoutingPolicy(response).also { cacheRoutingPolicy(response) }
    }

    suspend fun issueVpnConfig(
        region: String? = null,
        exitNodeId: String? = null,
        routeMode: String? = null,
        forceRotate: Boolean = false
    ): IssuedConfig {
        val session = ensureSession()
        val requestedRouteMode = routeMode?.normalizeRouteMode() ?: cachedDefaultRouteMode()
        val rotateForRouteModeChange = prefs.getBoolean(KEY_ROUTE_MODE_CHANGE_REQUIRES_ROTATE, false)
        val body = JSONObject()
            .put("deviceId", session.deviceId)
            .put("routeMode", requestedRouteMode)
            .put("forceRotate", forceRotate || rotateForRouteModeChange)
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
            .putString(KEY_ACTIVE_CLIENT_CONFIG_JSON, issued.clientConfigJson)
            .putString(KEY_ACTIVE_ROUTE_MODE, issued.routeMode.normalizeRouteMode())
            .putString(KEY_ACTIVE_REQUESTED_ROUTE_MODE, issued.requestedRouteMode?.normalizeRouteMode() ?: requestedRouteMode)
            .putString(KEY_ACTIVE_ROUTE_FALLBACK_REASON, issued.routeFallbackReason)
            .putInt(KEY_ACTIVE_CONFIG_SCHEMA_VERSION, ACTIVE_CONFIG_SCHEMA_VERSION)
            .remove(KEY_ROUTE_MODE_CHANGE_REQUIRES_ROTATE)
            .apply()
        return issued
    }

    fun activeClientConfigJson(): String? =
        prefs.getString(KEY_ACTIVE_CLIENT_CONFIG_JSON, null)?.takeIf { it.isNotBlank() }

    fun cachedDefaultRouteMode(): String =
        prefs.getString(KEY_USER_ROUTE_MODE, null)?.normalizeRouteMode()
            ?: prefs.getString(KEY_DEFAULT_ROUTE_MODE, null)?.normalizeRouteMode()
            ?: "SINGLE"

    fun isCascadeModeEnabled(): Boolean = cachedDefaultRouteMode() == "CASCADE"

    fun setCascadeModeEnabled(enabled: Boolean) {
        val nextRouteMode = if (enabled) "CASCADE" else "SINGLE"
        if (cachedDefaultRouteMode() == nextRouteMode) return
        prefs.edit()
            .putString(KEY_USER_ROUTE_MODE, nextRouteMode)
            .putBoolean(KEY_ROUTE_MODE_CHANGE_REQUIRES_ROTATE, true)
            .remove(KEY_ACTIVE_CONFIG_ID)
            .remove(KEY_ACTIVE_VLESS_URI)
            .remove(KEY_ACTIVE_CLIENT_CONFIG_JSON)
            .remove(KEY_ACTIVE_ROUTE_MODE)
            .remove(KEY_ACTIVE_REQUESTED_ROUTE_MODE)
            .remove(KEY_ACTIVE_ROUTE_FALLBACK_REASON)
            .remove(KEY_ACTIVE_CONFIG_SCHEMA_VERSION)
            .apply()
    }

    fun activeConfigMatchesDefaultRouteMode(): Boolean {
        val activeConfigId = activeConfigId ?: return true
        if (prefs.getInt(KEY_ACTIVE_CONFIG_SCHEMA_VERSION, 0) != ACTIVE_CONFIG_SCHEMA_VERSION) return false
        val requestedRouteMode = prefs.getString(KEY_ACTIVE_REQUESTED_ROUTE_MODE, null)?.normalizeRouteMode()
            ?: return true
        return activeConfigId.isNotBlank() && requestedRouteMode == cachedDefaultRouteMode()
    }

    fun telegramAccount(): TelegramAccount? {
        val id = prefs.getLong(KEY_TELEGRAM_ID, 0L).takeIf { it > 0L } ?: return null
        return TelegramAccount(
            id = id,
            username = prefs.getString(KEY_TELEGRAM_USERNAME, null),
            firstName = prefs.getString(KEY_TELEGRAM_FIRST_NAME, null)
        )
    }

    suspend fun loginWithTelegram(auth: TelegramAuthData): TelegramAccount {
        val fingerprint = getOrCreateDeviceFingerprint()
        val telegram = JSONObject()
            .put("id", auth.id)
            .put("auth_date", auth.authDate)
            .put("hash", auth.hash)
            .apply {
                auth.firstName?.let { put("first_name", it) }
                auth.lastName?.let { put("last_name", it) }
                auth.username?.let { put("username", it) }
                auth.photoUrl?.let { put("photo_url", it) }
            }
        val body = JSONObject()
            .put("telegram", telegram)
            .put("deviceFingerprint", fingerprint)
            .put("deviceName", android.os.Build.MODEL ?: "Android")
            .put("platform", "android")
            .put("appVersion", BuildConfig.VERSION_NAME)
            .toString()

        val response = requestText(path = "/auth/telegram", method = "POST", body = body)
        storeSession(JSONObject(response))
        return storeTelegramAccount(auth)
    }

    suspend fun startTelegramAppLogin(): TelegramAppLoginStart {
        val fingerprint = getOrCreateDeviceFingerprint()
        val body = JSONObject()
            .put("deviceFingerprint", fingerprint)
            .put("deviceName", android.os.Build.MODEL ?: "Android")
            .put("platform", "android")
            .put("appVersion", BuildConfig.VERSION_NAME)
            .toString()
        val response = requestText(path = "/auth/telegram/app-login/start", method = "POST", body = body)
        val json = JSONObject(response)
        return TelegramAppLoginStart(
            challengeId = json.getString("challengeId"),
            telegramAppUrl = json.getString("telegramAppUrl"),
            telegramWebUrl = json.getString("telegramWebUrl"),
            expiresIn = json.optLong("expiresIn", 300L)
        )
    }

    suspend fun pollTelegramAppLogin(challengeId: String): TelegramAppLoginResult {
        val response = requestText(path = "/auth/telegram/app-login/$challengeId")
        val json = JSONObject(response)
        val auth = json.optJSONObject("auth")
        if (json.optString("status") != "READY" || auth == null) {
            return TelegramAppLoginResult.Pending
        }
        storeSession(auth)
        val telegram = json.optJSONObject("telegram")
        val account = if (telegram != null) {
            storeTelegramAccount(
                TelegramAuthData(
                    id = telegram.getLong("id"),
                    authDate = 0L,
                    hash = "",
                    firstName = telegram.optStringOrNull("firstName"),
                    username = telegram.optStringOrNull("username")
                )
            )
        } else {
            telegramAccount()
        }
        return TelegramAppLoginResult.Ready(account)
    }

    suspend fun loadTelegramConfigs(): List<TelegramLinkedConfig> {
        val response = authorizedRequest(path = "/vpn/telegram-configs")
        val configs = JSONObject(response).optJSONArray("configs") ?: JSONArray()
        return buildList {
            for (index in 0 until configs.length()) {
                val item = configs.getJSONObject(index)
                add(
                    TelegramLinkedConfig(
                        nodeId = item.getString("nodeId"),
                        configName = item.optStringOrNull("configName")
                            ?: item.optStringOrNull("subId")
                            ?: item.optStringOrNull("email")
                            ?: "Telegram config ${index + 1}",
                        nodeName = item.optString("nodeName", "VPN node"),
                        region = item.optString("region", ""),
                        email = item.getString("email"),
                        enabled = item.optBoolean("enabled", true),
                        upBytes = item.optLong("upBytes", 0L),
                        downBytes = item.optLong("downBytes", 0L),
                        totalBytes = item.optLong("totalBytes", 0L),
                        expiryTime = item.optLong("expiryTime", 0L),
                        vlessUri = item.optStringOrNull("vlessUri")
                    )
                )
            }
        }
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

    fun activeVlessUri(): String? = prefs.getString(KEY_ACTIVE_VLESS_URI, null)

    fun clearSavedActiveConfig() = clearActiveConfig()

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

    private suspend fun loadClientSettings(): ClientSettings {
        val response = authorizedRequest(path = "/client/settings")
        val json = JSONObject(response)
        val previousRouteMode = prefs.getString(KEY_DEFAULT_ROUTE_MODE, null)?.normalizeRouteMode()
        val settings = ClientSettings(
            defaultRouteMode = json.optString("defaultRouteMode", "SINGLE").normalizeRouteMode(),
            cascadeEnabled = json.optBoolean("cascadeEnabled", false),
            cascadeFallbackToSingle = json.optBoolean("cascadeFallbackToSingle", true),
            clientConfigPreferred = json.optBoolean("clientConfigPreferred", true)
        )
        if (previousRouteMode != null && previousRouteMode != settings.defaultRouteMode) {
            clearActiveConfig()
        }
        prefs.edit()
            .putString(KEY_DEFAULT_ROUTE_MODE, settings.defaultRouteMode)
            .apply()
        return settings
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

    private suspend fun loadNotifications(): List<ServerNotification> {
        val response = authorizedRequest(path = "/notifications")
        val items = JSONObject(response).optJSONArray("notifications") ?: JSONArray()
        val locallyReadIds = prefs.getStringSet(KEY_READ_NOTIFICATION_IDS, emptySet()).orEmpty()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val id = item.getString("id")
                if (id in locallyReadIds) continue
                add(
                    ServerNotification(
                        id = id,
                        title = item.optString("title", "Сообщение"),
                        body = item.optString("body", ""),
                        severity = item.optString("severity", "INFO")
                    )
                )
            }
        }
    }

    private fun rememberReadNotification(notificationId: String) {
        if (notificationId.isBlank()) return
        val readIds = LinkedHashSet(prefs.getStringSet(KEY_READ_NOTIFICATION_IDS, emptySet()).orEmpty())
        readIds += notificationId
        while (readIds.size > MAX_LOCAL_READ_NOTIFICATION_IDS) {
            readIds.remove(readIds.first())
        }
        prefs.edit().putStringSet(KEY_READ_NOTIFICATION_IDS, readIds).apply()
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

    private fun storeTelegramAccount(auth: TelegramAuthData): TelegramAccount {
        val account = TelegramAccount(id = auth.id, username = auth.username, firstName = auth.firstName)
        prefs.edit()
            .putLong(KEY_TELEGRAM_ID, auth.id)
            .putString(KEY_TELEGRAM_USERNAME, auth.username)
            .putString(KEY_TELEGRAM_FIRST_NAME, auth.firstName)
            .apply()
        return account
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
            routeMode = json.optString("routeMode", "SINGLE").uppercase(),
            requestedRouteMode = json.optStringOrNull("requestedRouteMode"),
            routeFallbackReason = json.optStringOrNull("routeFallbackReason"),
            vlessUri = json.optStringOrNull("vlessUri"),
            clientConfigJson = json.optJSONObject("clientConfig")?.toString()
        )
    }

    private fun parseRoutingPolicy(response: String): RoutingPolicy {
        val json = JSONObject(response)
        val rules = json.optJSONArray("routeRules") ?: JSONArray()
        return RoutingPolicy(
            version = json.optInt("version", 1),
            defaultRoute = json.optString("defaultRoute", "VPN").uppercase(),
            routeRules = buildList {
                for (index in 0 until rules.length()) {
                    val item = rules.getJSONObject(index)
                    val values = item.optJSONArray("values") ?: JSONArray()
                    add(
                        RouteRule(
                            id = item.optString("id"),
                            source = item.optString("source", "USER").uppercase(),
                            defaultRuleKey = item.optStringOrNull("defaultRuleKey"),
                            name = item.optString("name", "Правило ${index + 1}"),
                            description = item.optString("description", ""),
                            enabled = item.optBoolean("enabled", true),
                            priority = item.optInt("priority", (index + 1) * 100),
                            matchType = item.optString("matchType", "DOMAIN_SUFFIX").uppercase(),
                            values = buildList {
                                for (valueIndex in 0 until values.length()) {
                                    values.optString(valueIndex).takeIf { it.isNotBlank() }?.let(::add)
                                }
                            },
                            action = item.optString("action", "VPN").uppercase(),
                            editable = item.optBoolean("editable", true)
                        )
                    )
                }
            },
            hash = json.optString("hash", ""),
            updatedAt = json.optString("updatedAt", "")
        )
    }

    private fun cacheRoutingPolicy(rawJson: String) {
        prefs.edit().putString(KEY_ROUTING_POLICY_JSON, rawJson).apply()
    }

    private fun getOrCreateDeviceFingerprint(): String {
        val existing = prefs.getString(KEY_DEVICE_FINGERPRINT, null)
        if (!existing.isNullOrBlank()) return existing
        val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
        val created = "android-${androidId ?: UUID.randomUUID()}"
        prefs.edit().putString(KEY_DEVICE_FINGERPRINT, created).apply()
        return created
    }

    private fun clearActiveConfig() {
        prefs.edit()
            .remove(KEY_ACTIVE_CONFIG_ID)
            .remove(KEY_ACTIVE_VLESS_URI)
            .remove(KEY_ACTIVE_CLIENT_CONFIG_JSON)
            .remove(KEY_ACTIVE_ROUTE_MODE)
            .remove(KEY_ACTIVE_REQUESTED_ROUTE_MODE)
            .remove(KEY_ACTIVE_ROUTE_FALLBACK_REASON)
            .remove(KEY_ACTIVE_CONFIG_SCHEMA_VERSION)
            .apply()
    }

    private fun clearSession() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_ACTIVE_CONFIG_ID)
            .remove(KEY_ACTIVE_VLESS_URI)
            .remove(KEY_ACTIVE_CLIENT_CONFIG_JSON)
            .remove(KEY_ACTIVE_ROUTE_MODE)
            .remove(KEY_ACTIVE_REQUESTED_ROUTE_MODE)
            .remove(KEY_ACTIVE_ROUTE_FALLBACK_REASON)
            .remove(KEY_ACTIVE_CONFIG_SCHEMA_VERSION)
            .remove(KEY_TELEGRAM_ID)
            .remove(KEY_TELEGRAM_USERNAME)
            .remove(KEY_TELEGRAM_FIRST_NAME)
            .apply()
    }

    private val activeConfigId: String?
        get() = prefs.getString(KEY_ACTIVE_CONFIG_ID, null)

    companion object {
        private const val KEY_READ_NOTIFICATION_IDS = "read_notification_ids"
        private const val MAX_LOCAL_READ_NOTIFICATION_IDS = 200
        private val locallyInactiveRevokeStatuses = setOf("REVOKED", "EXPIRED", "FAILED")

        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_FINGERPRINT = "device_fingerprint"
        private const val KEY_ACTIVE_CONFIG_ID = "active_config_id"
        private const val KEY_ACTIVE_VLESS_URI = "active_vless_uri"
        private const val KEY_ACTIVE_CLIENT_CONFIG_JSON = "active_client_config_json"
        private const val KEY_ACTIVE_ROUTE_MODE = "active_route_mode"
        private const val KEY_ACTIVE_REQUESTED_ROUTE_MODE = "active_requested_route_mode"
        private const val KEY_ACTIVE_ROUTE_FALLBACK_REASON = "active_route_fallback_reason"
        private const val KEY_ACTIVE_CONFIG_SCHEMA_VERSION = "active_config_schema_version"
        private const val KEY_DEFAULT_ROUTE_MODE = "default_route_mode"
        private const val KEY_USER_ROUTE_MODE = "user_route_mode"
        private const val KEY_ROUTE_MODE_CHANGE_REQUIRES_ROTATE = "route_mode_change_requires_rotate"
        private const val ACTIVE_CONFIG_SCHEMA_VERSION = 2
        private const val KEY_TELEGRAM_ID = "telegram_id"
        private const val KEY_TELEGRAM_USERNAME = "telegram_username"
        private const val KEY_TELEGRAM_FIRST_NAME = "telegram_first_name"
        private const val KEY_ROUTING_POLICY_JSON = "routing_policy_json"
    }
}

data class VpnBootstrap(
    val nodes: List<VpnNode>,
    val quota: QuotaStatus?,
    val notifications: List<ServerNotification>,
    val clientSettings: ClientSettings = ClientSettings.default(),
    val activeConfigId: String?
)

data class ClientSettings(
    val defaultRouteMode: String,
    val cascadeEnabled: Boolean,
    val cascadeFallbackToSingle: Boolean,
    val clientConfigPreferred: Boolean
) {
    companion object {
        fun default(): ClientSettings = ClientSettings(
            defaultRouteMode = "SINGLE",
            cascadeEnabled = false,
            cascadeFallbackToSingle = true,
            clientConfigPreferred = true
        )
    }
}

data class ServerNotification(
    val id: String,
    val title: String,
    val body: String,
    val severity: String
) {
    val displayText: String
        get() = listOf(title, body).filter { it.isNotBlank() }.joinToString(": ")
}

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
    val usedBytes: Long get() = gbToBytes(usedGb)
    val totalBytes: Long get() = gbToBytes(totalGb)

    private fun gbToBytes(value: Double): Long =
        if (value <= 0.0) 0L else (value * 1024.0 * 1024.0 * 1024.0).toLong()
}

data class IssuedConfig(
    val configId: String,
    val status: String,
    val expiresAt: String,
    val nodeName: String,
    val region: String,
    val routeMode: String,
    val requestedRouteMode: String?,
    val routeFallbackReason: String?,
    val vlessUri: String?,
    val clientConfigJson: String?
)

data class RevokeResult(
    val configId: String,
    val status: String,
    val revokedAt: String? = null
)

data class RoutingPolicy(
    val version: Int,
    val defaultRoute: String,
    val routeRules: List<RouteRule>,
    val hash: String = "",
    val updatedAt: String = ""
) {
    companion object {
        fun default(): RoutingPolicy = RoutingPolicy(
            version = 1,
            defaultRoute = "VPN",
            routeRules = emptyList()
        )
    }
}

data class RouteRule(
    val id: String,
    val source: String,
    val defaultRuleKey: String?,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val priority: Int,
    val matchType: String,
    val values: List<String>,
    val action: String,
    val editable: Boolean = true
)

data class TelegramAuthData(
    val id: Long,
    val authDate: Long,
    val hash: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val username: String? = null,
    val photoUrl: String? = null
)

data class TelegramAccount(
    val id: Long,
    val username: String?,
    val firstName: String?
) {
    val displayName: String
        get() = username?.let { "@$it" } ?: firstName ?: "id $id"
}

data class TelegramAppLoginStart(
    val challengeId: String,
    val telegramAppUrl: String,
    val telegramWebUrl: String,
    val expiresIn: Long
)

sealed interface TelegramAppLoginResult {
    data object Pending : TelegramAppLoginResult
    data class Ready(val account: TelegramAccount?) : TelegramAppLoginResult
}

data class TelegramLinkedConfig(
    val nodeId: String,
    val configName: String,
    val nodeName: String,
    val region: String,
    val email: String,
    val enabled: Boolean,
    val upBytes: Long,
    val downBytes: Long,
    val totalBytes: Long,
    val expiryTime: Long,
    val vlessUri: String?
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

private fun String.normalizeRouteMode(): String {
    val normalized = trim().uppercase()
    return if (normalized == "SINGLE" || normalized == "CASCADE") normalized else "SINGLE"
}

private fun JSONObject.optGb(gbName: String, bytesName: String): Double {
    if (has(gbName) && !isNull(gbName)) return optDouble(gbName, 0.0)
    val bytes = optLong(bytesName, 0L)
    return bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
}
