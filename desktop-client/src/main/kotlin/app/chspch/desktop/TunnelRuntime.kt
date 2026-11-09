package app.chspch.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class TunnelRuntime(private val directory: Path) : AutoCloseable {
    private var elevated: ElevatedTunnel? = null
    private var process: Process? = null
    private var config: Path? = null
    val authorized: Boolean get() = elevated?.authorized == true
    internal val elevationRequests: Int get() = elevated?.elevationRequests ?: 0
    val vpnConnections: Int get() = elevated?.vpnConnections?.get() ?: 0
    val running: Boolean get() = elevated?.running == true || process?.isAlive == true
    fun start(binary: Path, json: String) {
        check(!running) { "Ядро уже работает" }
        close() // Remove private config left by an unexpected core exit before starting again.
        require(Files.isRegularFile(binary)) { "Выберите исполняемый файл sing-box" }
        val version = ProcessBuilder(binary.toAbsolutePath().toString(), "version").redirectErrorStream(true).start()
        val output = java.util.concurrent.CompletableFuture.supplyAsync { version.inputStream.bufferedReader().use { it.readText().take(4096) } }
        if (!version.waitFor(5, TimeUnit.SECONDS)) { version.destroyForcibly(); error("Ядро не отвечает") }
        require(output.get(1, TimeUnit.SECONDS).contains("sing-box version 1.14.4")) { "Нужен sing-box 1.14.4" }
        val path = Files.createTempFile(directory, "tunnel-", ".json")
        StateStore.restrict(path); Files.writeString(path, json); config = path
        try {
            val validation = ProcessBuilder(binary.toAbsolutePath().toString(), "check", "-c", path.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!validation.waitFor(10, TimeUnit.SECONDS)) { validation.destroyForcibly(); error("Проверка конфигурации не завершилась") }
            require(validation.exitValue() == 0) { "sing-box отклонил конфигурацию" }
            val inbounds = org.json.JSONObject(json).getJSONArray("inbounds")
            if ((0 until inbounds.length()).any { inbounds.getJSONObject(it).optString("type") == "tun" }) {
                val helper = elevated?.takeIf { it.authorized } ?: run { elevated?.close(); ElevatedTunnel(directory) }
                elevated = helper
                helper.start(binary, json)
                return
            }
            val started = AtomicBoolean(false)
            val child = ProcessBuilder(binary.toAbsolutePath().toString(), "run", "-c", path.toString())
                .directory(directory.toFile()).redirectErrorStream(true).start()
            process = child
            thread(name = "chspch-core-output", isDaemon = true) {
                child.inputStream.bufferedReader().useLines { lines -> lines.forEach {
                    // Never persist runtime payload, destinations or credentials.
                    if (it.contains("sing-box started")) started.set(true)
                } }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!started.get() && child.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
            check(started.get() && child.isAlive) { "Ядро не запустилось. Проверьте права TUN, порт и доступность правил." }
        } catch (error: Exception) { close(); throw error }
    }
    fun shutdown() { try { close() } finally { elevated?.close(); elevated = null } }
    @Synchronized override fun close() {
        elevated?.stop()
        process?.let {
            it.destroy()
            if (!it.waitFor(5, TimeUnit.SECONDS)) {
                it.destroyForcibly()
                check(it.waitFor(5, TimeUnit.SECONDS)) { "Не удалось остановить VPN-ядро" }
            }
        }
        process = null
        config?.let(Files::deleteIfExists); config = null
    }
}
