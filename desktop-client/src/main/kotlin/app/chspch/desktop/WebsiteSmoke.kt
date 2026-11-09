package app.chspch.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Opt-in check of the user's requested public website. Never logs account/config data. */
fun main() {
    check(System.getenv("CHSPCH_WEBSITE_SMOKE") == "1")
    val store = StateStore()
    FileChannel.open(store.directory.resolve("desktop.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
        val lock = channel.tryLock() ?: error("Close Chspch first")
        val api = DesktopApi(store)
        val runtime = TunnelRuntime(store.directory)
        var issued = false
        try {
            check(store.data.optBoolean("linked"))
            var policy = api.policy()
            val rules = policy.getJSONArray("routeRules")
            val present = (0 until rules.length()).map(rules::getJSONObject).any { r ->
                r.optBoolean("enabled") && r.optString("action") == "VPN" &&
                    (0 until r.getJSONArray("values").length()).any { r.getJSONArray("values").getString(it) == "myanimelist.net" }
            }
            println("Saved enabled myanimelist.net rule: $present")
            if (!present) {
                rules.put(JSONObject().put("name", "myanimelist.net").put("enabled", true).put("priority", 10000)
                    .put("matchType", "DOMAIN_SUFFIX").put("values", JSONArray().put("myanimelist.net")).put("action", "VPN"))
                policy = api.updatePolicy(policy)
                println("Requested website rule saved and returned by API")
            }
            val node = api.nodes().first { it.health == "HEALTHY" && it.name.contains("Франция") }
            val config = api.issue(node); issued = true
            val mode = if (System.getenv("CHSPCH_WEBSITE_PROXY") == "1") TunnelMode.PROXY else TunnelMode.TUN
            runtime.start(findCore(), DesktopConfig.create(config, policy, api.baseUrl, mode).toString())
            for (host in listOf("myanimelist.net", "www.myanimelist.net")) {
                val before = runtime.vpnConnections
                val c = connection("https://$host/", mode).apply { setRequestProperty("Connection", "close") }
                try {
                    val code = c.responseCode
                    val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    Thread.sleep(300)
                    println("$host: HTTP $code; VPN outbound recorded: ${runtime.vpnConnections > before}; HTML: ${body.contains("<html", true)}")
                    check(code in 200..399) { "Website returned HTTP $code" }
                } finally { c.disconnect() }
            }
            if (System.getenv("CHSPCH_WEBSITE_RECONNECT") == "1") {
                runtime.close()
                check(!runtime.running && runtime.authorized)
                runtime.start(findCore(), DesktopConfig.create(config, policy, api.baseUrl, mode).toString())
                val status = probe("https://myanimelist.net", mode)
                println("Reconnected website HTTP $status; elevation requests: ${runtime.elevationRequests}")
                check(status in 200..399 && runtime.elevationRequests == 1)
            }

        } finally {
            try { runtime.shutdown(); if (issued) api.revoke(); println("Test tunnel stopped; issued config revoked") }
            finally { lock.release() }
        }
    }
}
