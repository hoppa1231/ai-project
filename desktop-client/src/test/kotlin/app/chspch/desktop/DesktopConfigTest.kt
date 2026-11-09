package app.chspch.desktop

import org.json.JSONObject
import org.json.JSONArray
import java.nio.file.Files
import kotlin.test.*

class DesktopConfigTest {
    private fun issued() = JSONObject("""{"clientConfig":{"format":"sing-box","outbounds":[{"type":"vless","tag":"vpn","server":"192.0.2.1","server_port":443,"uuid":"00000000-0000-4000-8000-000000000001"}],"route":{"final":"vpn"}}}""")
    private fun policy() = JSONObject("""{"routeRules":[
        {"name":"Telegram","enabled":true,"priority":10,"matchType":"DOMAIN_SUFFIX","values":["telegram.org"],"action":"VPN"},
        {"enabled":true,"priority":20,"matchType":"IP_CIDR","values":["198.51.100.0/24"],"action":"BLOCK"},
        {"enabled":false,"matchType":"DOMAIN","values":["disabled.example"],"action":"VPN"},
        {"enabled":true,"matchType":"APP_PACKAGE","values":["org.telegram.messenger"],"action":"VPN"}
    ]}""")
    @Test fun `selective routing never defaults to VPN and converts block to reject`() {
        val config = DesktopConfig.create(issued(), policy(), "https://api.example", TunnelMode.PROXY)
        val route = config.getJSONObject("route")
        assertEquals("direct", route.getString("final"))
        val rules = route.getJSONArray("rules").toString()
        assertTrue(rules.contains("telegram.org")); assertTrue(rules.contains("reject"))
        assertFalse(rules.contains("disabled.example")); assertFalse(rules.contains("package_name"))
        assertEquals("127.0.0.1", config.getJSONArray("inbounds").getJSONObject(0).getString("listen"))
        assertEquals(1, config.getJSONArray("inbounds").length())
        val dns = config.getJSONObject("dns").getJSONArray("rules").getJSONObject(0)
        assertEquals("remote", dns.getString("server"))
    }
    @Test fun `tun bypasses vpn server and bootstrap dns`() {
        val tun = DesktopConfig.create(issued(), policy(), "https://api.example", TunnelMode.TUN)
            .getJSONArray("inbounds").getJSONObject(1)
        assertTrue(tun.getBoolean("auto_route")); assertTrue(tun.getBoolean("strict_route"))
        assertTrue(tun.getJSONArray("route_exclude_address").toString().contains("192.0.2.1/32"))
        assertTrue(tun.getJSONArray("route_exclude_address").toString().contains("1.1.1.1/32"))
    }
    @Test fun `desktop process rules follow protected direct domains and use native paths`() {
        val config = DesktopConfig.create(issued(), policy(), "https://api.example", TunnelMode.TUN, listOf("/opt/example/app"))
        val route = config.getJSONObject("route")
        val rules = route.getJSONArray("rules")
        val entries = (0 until rules.length()).map { rules.getJSONObject(it) }
        val processIndex = entries.indexOfFirst { it.has("process_path") }
        val ruIndex = entries.indexOfFirst { it.optJSONArray("domain_suffix")?.toString()?.contains("xn--p1ai") == true }
        assertTrue(ruIndex in 0 until processIndex)
        assertEquals("vpn", entries[processIndex].getString("outbound"))
        val tun = config.getJSONArray("inbounds").getJSONObject(1)
        assertEquals("hijack", tun.getString("dns_mode"))
    }
    @Test fun `unknown active rules fail instead of silently routing direct`() {
        assertFailsWith<IllegalStateException> {
            DesktopConfig.create(issued(), JSONObject("""{"routeRules":[{"matchType":"UNKNOWN","values":["x"]}]}"""), "https://api.example", TunnelMode.PROXY)
        }
    }
    @Test fun `private session persists fingerprint and credentials atomically`() {
        val dir = Files.createTempDirectory("chspch-state-test")
        try {
            val store = StateStore(dir)
            val id = store.data.getString("fingerprint")
            store.data.put("accessToken", "synthetic-test-token"); store.save()
            val restored = StateStore(dir)
            assertEquals(id, restored.data.getString("fingerprint"))
            assertEquals("synthetic-test-token", restored.data.getString("accessToken"))
            if (Files.getFileStore(dir).supportsFileAttributeView("posix"))
                assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("session.json"))))
        } finally { Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    @Test fun `generated configs pass real core check when explicitly enabled`() {
        val binary = System.getenv("CHSPCH_SING_BOX") ?: return
        val dir = Files.createTempDirectory("chspch-core-check")
        try {
            TunnelMode.entries.forEach { mode ->
                val file = dir.resolve("test.json")
                Files.writeString(file, DesktopConfig.create(issued(), policy(), "https://api.example", mode).toString())
                val process = ProcessBuilder(binary, "check", "-c", file.toString()).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.waitFor(), output)
            }
        } finally { Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
