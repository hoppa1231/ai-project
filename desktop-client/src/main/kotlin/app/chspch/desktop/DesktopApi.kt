package app.chspch.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

const val DESKTOP_VERSION = "1.0.5"

class ApiFailure(val status: Int) : RuntimeException(when(status) {
    401 -> "Сессия истекла. Войдите через Telegram повторно."
    403 -> "Действие недоступно для текущего аккаунта."
    409 -> "Состояние изменилось. Обновите список и повторите."
    else -> "Сервер вернул HTTP $status"
})
data class Node(val id: String, val name: String, val health: String) {
    override fun toString() = name + if (health == "HEALTHY") "" else " · недоступен"
}

/** Calls are serialized by the controller; refresh cannot race another rotation. */
class DesktopApi(val store: StateStore, val baseUrl: String = "https://tech-supp-test.ru") {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    init {
        val uri = URI(baseUrl)
        require(uri.scheme == "https" || uri.host in listOf("127.0.0.1", "localhost")) { "API должен использовать HTTPS" }
        val previous = store.data.optString("apiBaseUrl")
        require(previous.isBlank() || previous == baseUrl) { "Для другого API нужен отдельный каталог данных" }
        store.data.put("apiBaseUrl", baseUrl); store.save()
    }
    private fun request(path: String, method: String = "GET", body: JSONObject? = null, token: String? = null): String {
        val builder = HttpRequest.newBuilder(URI(baseUrl.trimEnd('/') + path))
            .timeout(Duration.ofSeconds(25)).header("Accept", "application/json")
            .header("User-Agent", "Chspch-Desktop/$DESKTOP_VERSION")
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (path == "/vpn/issue") builder.header("Idempotency-Key", UUID.randomUUID().toString())
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody())
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw ApiFailure(response.statusCode())
        return response.body()
    }
    private fun storeAuth(auth: JSONObject) {
        for (key in listOf("accessToken", "refreshToken", "deviceId")) store.data.put(key, auth.getString(key))
        store.save()
    }
    private fun device() = JSONObject().put("deviceFingerprint", store.data.getString("fingerprint"))
        .put("deviceName", "ЧСПЧ Desktop").put("platform", if (System.getProperty("os.name").startsWith("Windows")) "windows" else "linux")
        .put("appVersion", DESKTOP_VERSION)
    private fun ensureSession() {
        if (store.data.optString("accessToken").isNotBlank()) return
        if (store.data.optBoolean("linked")) error("Войдите через Telegram повторно")
        storeAuth(JSONObject(request("/auth/guest", "POST", device())))
    }
    private fun authorized(path: String, method: String = "GET", body: JSONObject? = null): String {
        ensureSession()
        try { return request(path, method, body, store.data.getString("accessToken")) }
        catch (error: ApiFailure) { if (error.status != 401) throw error }
        val refresh = store.data.optString("refreshToken")
        if (refresh.isBlank()) throw ApiFailure(401)
        // Network failures and invalid refresh never erase a linked account.
        storeAuth(JSONObject(request("/auth/refresh", "POST", JSONObject().put("refreshToken", refresh))))
        return request(path, method, body, store.data.getString("accessToken"))
    }
    fun nodes(): List<Node> {
        val array = JSONArray(authorized("/nodes"))
        return (0 until array.length()).map { array.getJSONObject(it).let { node ->
            Node(node.getString("id"), node.getString("name"), node.optString("health", "UNKNOWN"))
        } }
    }
    fun policy(): JSONObject = JSONObject(authorized("/policy/current"))
    fun updatePolicy(policy: JSONObject): JSONObject {
        val source = policy.getJSONArray("routeRules")
        val rules = JSONArray()
        for (index in 0 until source.length()) {
            val rule = source.getJSONObject(index)
            val item = JSONObject()
            if (rule.optString("source") == "USER" && rule.optString("id").isNotBlank()) item.put("id", rule.getString("id"))
            for (key in listOf("defaultRuleKey", "name", "enabled", "priority", "matchType", "values", "action"))
                if (rule.has(key) && !rule.isNull(key)) item.put(key, rule.get(key))
            rules.put(item)
        }
        return JSONObject(authorized("/policy/current", "PUT", JSONObject().put("ifVersion", policy.getInt("version"))
            .put("defaultRoute", "DIRECT").put("routeRules", rules)
            .put("includeApps", JSONArray()).put("excludeApps", JSONArray())
            .put("includeDomains", JSONArray()).put("excludeDomains", JSONArray())))
    }
    fun issue(node: Node): JSONObject {
        ensureSession()
        val issued = JSONObject(authorized("/vpn/issue", "POST", JSONObject()
            .put("deviceId", store.data.getString("deviceId")).put("exitNodeId", node.id)
            .put("routeMode", "SINGLE").put("forceRotate", false)))
        store.data.put("configId", issued.getString("configId")); store.save()
        return issued
    }
    fun revoke() {
        // Pending guest configs must be revoked with their original session, not the new account.
        val pending = store.data.optJSONArray("pendingRevocations") ?: JSONArray()
        var failed: Exception? = null
        var index = 0
        while (index < pending.length()) {
            val old = pending.getJSONObject(index)
            try {
                fun remove(): JSONObject = JSONObject(request("/vpn/revoke", "POST", JSONObject()
                    .put("configId", old.getString("configId")).put("reason", "desktop_login"), old.getString("accessToken")))
                val result = try { remove() } catch (error: ApiFailure) {
                    if (error.status != 401) throw error
                    val auth = JSONObject(request("/auth/refresh", "POST", JSONObject().put("refreshToken", old.getString("refreshToken"))))
                    old.put("accessToken", auth.getString("accessToken")).put("refreshToken", auth.getString("refreshToken"))
                    store.data.put("pendingRevocations", pending); store.save()
                    remove()
                }
                check(result.optString("status") in listOf("REVOKED", "EXPIRED", "FAILED")) { "Отзыв гостевого доступа ещё не подтверждён" }
                pending.remove(index); store.data.put("pendingRevocations", pending); store.save()
            } catch (error: Exception) { if (failed == null) failed = error; index++ }
        }
        // An unreachable old node must not prevent revoking the CURRENT config.
        val id = store.data.optString("configId").takeIf { it.isNotBlank() }
        if (id != null) try {
            val result = JSONObject(authorized("/vpn/revoke", "POST", JSONObject().put("configId", id).put("reason", "desktop_disconnect")))
            check(result.optString("status") in listOf("REVOKED", "EXPIRED", "FAILED")) { "Отзыв ещё не подтверждён сервером" }
            store.data.remove("configId"); store.save()
        } catch (error: Exception) { if (failed == null) failed = error }
        failed?.let { throw it }
    }
    fun startLogin(): JSONObject = JSONObject(request("/auth/telegram/app-login/start", "POST", device()))
    fun pollLogin(challenge: String): Boolean {
        val result = try { JSONObject(request("/auth/telegram/app-login/$challenge")) }
        catch (error: ApiFailure) {
            if (error.status == 404) throw IllegalStateException("Ссылка входа истекла. Нажмите «Войти через Telegram» ещё раз и откройте новую ссылку.")
            throw error
        }
        if (result.optString("status") != "READY") return false
        val auth = result.getJSONObject("auth")
        // Validate before changing anything: READY is consumed once by the server.
        for (key in listOf("accessToken", "refreshToken", "deviceId")) auth.getString(key)
        if (store.data.optString("configId").isNotBlank()) {
            val pending = store.data.optJSONArray("pendingRevocations") ?: JSONArray()
            pending.put(JSONObject().put("configId", store.data.getString("configId"))
                .put("accessToken", store.data.getString("accessToken"))
                .put("refreshToken", store.data.getString("refreshToken")))
            store.data.put("pendingRevocations", pending)
            store.data.remove("configId")
        }
        store.data.put("linked", true)
        storeAuth(auth) // Save new account and old pending cleanup together.
        return true
    }
}
