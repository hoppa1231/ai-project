package app.chspch.desktop

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path

/** Explicitly invoked, never runs as part of the unit tests. Prints no credentials. */
fun main() {
    val binary = Path.of(System.getenv("CHSPCH_SING_BOX") ?: error("Set CHSPCH_SING_BOX"))
    val dir = System.getenv("CHSPCH_SMOKE_STATE")?.let(Path::of) ?: Files.createTempDirectory("chspch-smoke-")
    val store = StateStore(dir)
    val api = DesktopApi(store)
    val runtime = TunnelRuntime(dir)
    var revoked = false
    try {
        if (store.data.has("configId")) { api.revoke(); println("Previous smoke config revoked") }
        val nodes = api.nodes()
        println("Guest nodes: ${nodes.size}; healthy: ${nodes.count { it.health == "HEALTHY" }}")
        nodes.forEach { println("Guest node: ${it.name}; health: ${it.health}") }
        val node = nodes.firstOrNull { it.health == "HEALTHY" && (System.getenv("CHSPCH_SMOKE_NODE")?.let { name -> it.name.contains(name, true) } ?: true) } ?: error("No healthy guest node")
        val policy = api.policy()
        val issued = api.issue(node)
        val mode = if (System.getenv("CHSPCH_SMOKE_TUN") == "1") TunnelMode.TUN else TunnelMode.PROXY
        val json = DesktopConfig.create(issued, policy, api.baseUrl, mode)
        check(json.getJSONObject("route").getString("final") == "direct")
        runtime.start(binary, json.toString())
        println("Core startup: OK; default route: DIRECT; mode: $mode")
        if (mode == TunnelMode.TUN && System.getProperty("os.name").startsWith("Linux")) { check(Files.exists(Path.of("/sys/class/net/chspch0"))); println("OS TUN interface: present") }
        val code = probe("https://telegram.org", mode)
        println("Telegram with $mode routing: HTTP $code")
        val direct = probe("https://example.com", mode)
        println("Ordinary site with DIRECT policy: HTTP $direct")
        check(direct in 200..399) { "Direct route failed" }
        check(code in 200..299) { "Telegram probe did not succeed" }
        if (System.getenv("CHSPCH_SMOKE_CRASH") == "1") { println("Simulating abrupt GUI exit"); kotlin.system.exitProcess(23) }
    } finally {
        runtime.shutdown()
        println("Core stopped: ${!runtime.running}")
        try { api.revoke(); revoked = true; println("Server revoke: confirmed") }
        finally {
            // Preserve durable config ID on failed cleanup; do not discard evidence needed for retry.
            if (revoked) Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            else println("Cleanup pending; private state kept at $dir")
        }
    }
}
