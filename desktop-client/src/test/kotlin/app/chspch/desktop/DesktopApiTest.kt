package app.chspch.desktop

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.*

class DesktopApiTest {
    @Test fun `refresh network failure preserves linked account and never creates guest`() {
        withServer { server, store, api ->
            var guests = 0
            store.data.put("accessToken", "test-old").put("refreshToken", "test-refresh")
                .put("deviceId", "test-device").put("linked", true); store.save()
            server.createContext("/nodes") { exchange -> exchange.sendResponseHeaders(401, -1); exchange.close() }
            server.createContext("/auth/refresh") { exchange -> exchange.sendResponseHeaders(503, -1); exchange.close() }
            server.createContext("/auth/guest") { exchange -> guests++; exchange.sendResponseHeaders(500, -1); exchange.close() }
            assertEquals(503, assertFailsWith<ApiFailure> { api.nodes() }.status)
            val restored = StateStore(store.directory)
            assertTrue(restored.data.getBoolean("linked"))
            assertEquals("test-refresh", restored.data.getString("refreshToken"))
            assertEquals(0, guests)
        }
    }
    @Test fun `failed revoke retains config id for explicit retry`() {
        withServer { server, store, api ->
            store.data.put("accessToken", "test-access").put("configId", "test-config"); store.save()
            var succeed = false
            server.createContext("/vpn/revoke") { exchange ->
                val raw = if (succeed) """{"status":"REVOKED"}""" else "{}"
                val bytes = raw.toByteArray()
                exchange.sendResponseHeaders(if (succeed) 200 else 502, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            assertEquals(502, assertFailsWith<ApiFailure> { api.revoke() }.status)
            assertEquals("test-config", StateStore(store.directory).data.getString("configId"))
            succeed = true; api.revoke()
            assertFalse(StateStore(store.directory).data.has("configId"))
        }
    }
    @Test fun `login keeps guest credentials for revoke while persisting new account`() {
        withServer { server, store, api ->
            store.data.put("accessToken", "guest-access").put("refreshToken", "guest-refresh")
                .put("deviceId", "guest-device").put("configId", "guest-config"); store.save()
            var ready = false
            server.createContext("/auth/telegram/app-login/test") { exchange ->
                val raw = if (ready) """{"status":"READY","auth":{"accessToken":"linked-access","refreshToken":"linked-refresh","deviceId":"linked-device"}}"""
                    else """{"status":"PENDING"}"""
                val bytes = raw.toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            var revokeToken: String? = null
            var revokeSucceeds = false
            server.createContext("/vpn/revoke") { exchange ->
                revokeToken = exchange.requestHeaders.getFirst("Authorization")
                val bytes = """{"status":"REVOKED"}""".toByteArray()
                exchange.sendResponseHeaders(if (revokeSucceeds) 200 else 502, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            assertFalse(api.pollLogin("test"))
            assertEquals("guest-access", store.data.getString("accessToken"))
            ready = true
            assertTrue(api.pollLogin("test"))
            assertEquals("linked-access", StateStore(store.directory).data.getString("accessToken"))
            assertTrue(store.data.getBoolean("linked")); assertFalse(store.data.has("configId"))
            assertFailsWith<ApiFailure> { api.revoke() }
            assertEquals("Bearer guest-access", revokeToken)
            assertEquals(1, StateStore(store.directory).data.getJSONArray("pendingRevocations").length())
            revokeSucceeds = true; api.revoke()
            assertEquals(0, StateStore(store.directory).data.getJSONArray("pendingRevocations").length())
            assertEquals("linked-access", store.data.getString("accessToken"))
        }
    }
    @Test fun `unreachable pending guest never blocks revoking current config`() {
        withServer { server, store, api ->
            store.data.put("accessToken", "current-access").put("configId", "current-config")
                .put("pendingRevocations", org.json.JSONArray().put(JSONObject().put("configId", "guest-config")
                    .put("accessToken", "guest-access").put("refreshToken", "guest-refresh"))); store.save()
            var currentRevoked = false
            server.createContext("/vpn/revoke") { exchange ->
                val guest = exchange.requestHeaders.getFirst("Authorization") == "Bearer guest-access"
                if (!guest) currentRevoked = true
                val bytes = """{"status":"REVOKED"}""".toByteArray()
                exchange.sendResponseHeaders(if (guest) 502 else 200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            assertEquals(502, assertFailsWith<ApiFailure> { api.revoke() }.status)
            assertTrue(currentRevoked)
            assertFalse(StateStore(store.directory).data.has("configId"))
            assertEquals(1, StateStore(store.directory).data.getJSONArray("pendingRevocations").length())
        }
    }
    private fun withServer(block: (HttpServer, StateStore, DesktopApi) -> Unit) {
        val dir = Files.createTempDirectory("chspch-api-test")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try {
            val store = StateStore(dir)
            block(server, store, DesktopApi(store, "http://127.0.0.1:${server.address.port}"))
        } finally {
            server.stop(0)
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
