package app.chspch.desktop

import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ElevationDenied(message: String) : IllegalStateException(message)

/** Only the small helper is elevated; the GUI never receives administrator rights. */
class ElevatedTunnel(private val directory: Path) : AutoCloseable {
    private var socket: Socket? = null
    private var launcher: Process? = null
    private val alive = AtomicBoolean(false)
    val vpnConnections = java.util.concurrent.atomic.AtomicInteger(0)
    val directConnections = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var stopped = CountDownLatch(1)
    private val terminated = AtomicBoolean(false)
    val authorized: Boolean get() = socket?.isClosed == false && !terminated.get()
    private var ready = CountDownLatch(1)
    @Volatile private var failure: String? = null
    var elevationRequests: Int = 0; private set
    val running: Boolean get() = alive.get()
    @Synchronized fun start(binary: Path, json: String) {
        failure = null; ready = CountDownLatch(1); stopped = CountDownLatch(1)
        vpnConnections.set(0); directConnections.set(0)
        if (authorized) {
            send(json)
            check(ready.await(50, TimeUnit.SECONDS) && running) { failure ?: "TUN не запустился" }
            return
        }
        terminated.set(false)
        val windows = System.getProperty("os.name").startsWith("Windows")
        val helper = binary.toAbsolutePath().parent.parent.resolve("helper")
            .resolve("chspch-tun-helper" + if (windows) ".exe" else "")
        require(Files.isRegularFile(helper)) { "Помощник TUN отсутствует. Используйте полный пакет ЧСПЧ." }
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val rendezvous = Files.createTempFile(directory, "tun-handshake-", ".json")
        StateStore.restrict(rendezvous)
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 250
            Files.writeString(rendezvous, JSONObject().put("port", server.localPort).put("token", token).toString())
            try {
                val command = if (windows) {
                    fun ps(value: String) = "'" + value.replace("'", "''") + "'"
                    // Start-Process reparses ArgumentList: use quoted paths, never config or tokens on the command line.
                    val arguments = "--rendezvous \"${rendezvous.toAbsolutePath()}\" --core \"${binary.toAbsolutePath()}\""
                    listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                        "try { Start-Process -FilePath ${ps(helper.toString())} -ArgumentList ${ps(arguments)} -Verb RunAs -Wait -ErrorAction Stop; exit 0 } catch { exit 1 }")
                } else listOf("pkexec", helper.toString(), "--rendezvous", rendezvous.toString(), "--core", binary.toAbsolutePath().toString())
                elevationRequests++
                launcher = ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
                while (socket == null && System.nanoTime() < deadline) {
                    if (launcher?.isAlive != true) throw ElevationDenied("Разрешение на запуск TUN не получено")
                    try {
                        val candidate = server.accept().apply { soTimeout = 2000 }
                        val reader = candidate.getInputStream().bufferedReader()
                        val greeting = try { reader.readLine() } catch (_: Exception) { candidate.close(); continue }
                        if (greeting != token) { candidate.close(); continue }
                        candidate.soTimeout = 0
                        socket = candidate
                        val writer = candidate.getOutputStream().bufferedWriter()
                        synchronized(writer) { writer.write(json); writer.newLine(); writer.flush() }
                        thread(name = "chspch-helper-events", isDaemon = true) {
                            try {
                                while (true) when (reader.readLine() ?: break) {
                                    "READY" -> { alive.set(true); ready.countDown() }
                                    "ROUTED_VPN" -> vpnConnections.incrementAndGet()
                                    "ROUTED_DIRECT" -> directConnections.incrementAndGet()
                                    "STOPPED" -> { alive.set(false); stopped.countDown() }
                                    "EXITED" -> { alive.set(false); ready.countDown(); stopped.countDown(); failure = "VPN-ядро остановилось" }
                                    "INVALID_CONFIG" -> { failure = "Помощник отклонил конфигурацию TUN"; ready.countDown() }
                                    "CORE_MISMATCH" -> { failure = "Проверка VPN-ядра не пройдена. Переустановите ЧСПЧ."; ready.countDown() }
                                    "START_FAILED" -> { failure = "Не удалось запустить VPN-ядро"; ready.countDown() }
                                    "PERMISSION_DENIED" -> { failure = "Системе не удалось предоставить права TUN"; ready.countDown() }
                                }
                            } catch (_: Exception) { failure = "Связь с помощником TUN потеряна" }
                            finally { terminated.set(true); alive.set(false); ready.countDown(); stopped.countDown() }
                        }
                        thread(name = "chspch-helper-heartbeat", isDaemon = true) {
                            try { while (!candidate.isClosed && !terminated.get()) {
                                Thread.sleep(2000)
                                send("ping")
                            } } catch (_: Exception) { runCatching { candidate.close() } }
                        }
                    } catch (_: SocketTimeoutException) { }
                }
                if (socket == null) throw ElevationDenied("Время ожидания системного разрешения истекло")
                check(ready.await(50, TimeUnit.SECONDS) && running) { failure ?: "TUN не запустился. Проверьте доступность сервера и сетевые права." }
            } catch (error: Exception) { close(); throw error }
            finally { Files.deleteIfExists(rendezvous) }
        }
    }
    private fun send(line: String) {
        val active = socket ?: error("Нет связи с помощником TUN")
        synchronized(active) { active.getOutputStream().write((line+"\n").toByteArray()); active.getOutputStream().flush() }
    }
    @Synchronized fun stop() {
        if (!authorized) { alive.set(false); return }
        stopped = CountDownLatch(1)
        send("stop")
        check(stopped.await(12, TimeUnit.SECONDS)) { "Помощник не подтвердил остановку TUN. Проверьте системную сеть." }
        alive.set(false)
    }
    @Synchronized override fun close() {
        try { if (authorized) { stop(); send("quit") } }
        finally { socket?.close(); socket = null; alive.set(false); terminated.set(true) }
    }
}
