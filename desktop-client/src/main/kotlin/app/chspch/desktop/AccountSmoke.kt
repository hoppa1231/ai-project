package app.chspch.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Explicit opt-in; stop the GUI first. No account/profile/exit IP is logged. */
fun main() {
    check(System.getenv("CHSPCH_ACCOUNT_SMOKE") == "1") { "Explicit CHSPCH_ACCOUNT_SMOKE=1 required" }
    val store = StateStore()
    val channel = FileChannel.open(store.directory.resolve("desktop.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val lock = channel.tryLock() ?: error("Close the GUI before this opt-in test")
    val runtime = TunnelRuntime(store.directory)
    val api = DesktopApi(store)
    fun exitIp(): String {
        val c = connection("https://api.ipify.org", TunnelMode.TUN).apply { setRequestProperty("Connection", "close") }
        return try { c.inputStream.bufferedReader().use { it.readText().trim() }.also {
            check(it.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) { "Exit-IP probe returned an invalid response" }
        } } finally { c.disconnect() }
    }
    try {
        check(store.data.optBoolean("linked")) { "Linked account required" }
        api.revoke()
        val directIp = exitIp()
        val node = api.nodes().firstOrNull { it.health == "HEALTHY" && it.name.contains("Франция", true) }
            ?: error("No healthy France node")
        val policy = api.policy()
        // Diagnostic-only rule; never PUT or persist it to the user's policy.
        val rules = policy.optJSONArray("routeRules") ?: JSONArray().also { policy.put("routeRules", it) }
        rules.put(JSONObject().put("enabled", true).put("priority", -1).put("matchType", "DOMAIN")
            .put("values", JSONArray().put("api.ipify.org")).put("action", "VPN"))
        val issued = api.issue(node)
        val outboundServer = issued.getJSONObject("clientConfig").getJSONArray("outbounds").getJSONObject(0).optString("server")
        println("Baseline already equals VPN server address: ${directIp == outboundServer}")
        runtime.start(findCore(), DesktopConfig.create(issued, policy, api.baseUrl, TunnelMode.TUN).toString())
        val tg = probe("https://telegram.org", TunnelMode.TUN)
        val ordinary = probe("https://example.com", TunnelMode.TUN)
        println("Authorized system TUN: Telegram HTTP $tg; ordinary site HTTP $ordinary")
        check(tg in 200..299 && ordinary in 200..399)
        Thread.sleep(300)
        val before = runtime.vpnConnections
        val routedIp = exitIp()
        Thread.sleep(300)
        val recorded = runtime.vpnConnections > before
        val differs = routedIp != directIp
        println("Diagnostic HTTPS request recorded by VPN outbound: $recorded")
        println("VPN egress matches selected server: ${routedIp == outboundServer}")
        println("VPN egress differs from initial network: $differs")
        check(recorded && (differs || routedIp == outboundServer)) { "VPN route was not confirmed" }
    } finally {
        try { runtime.shutdown(); println("Authorized TUN stopped: ${!runtime.running}"); api.revoke(); println("Config revoke: confirmed") }
        finally { lock.release(); channel.close() }
    }
}
