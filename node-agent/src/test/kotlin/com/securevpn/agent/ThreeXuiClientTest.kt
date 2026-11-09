package com.securevpn.agent

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ThreeXuiClientTest {
    @Test fun expiredPanelSessionIsRenewed() {
        val calls = AtomicInteger()
        val logins = AtomicInteger()
        withPanel(logins) { server, client ->
            server.createContext("/panel/api/inbounds/list") { exchange ->
                val response = if (calls.incrementAndGet() == 1) {
                    """{"success":false,"msg":"login required"}"""
                } else """{"success":true,"obj":[]}"""
                val bytes = response.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            assertTrue(client.listClients().isEmpty())
            assertEquals(2, logins.get())
        }
    }

    @Test fun rejectedCreationCannotBeReportedAsSuccess() {
        withPanel(AtomicInteger()) { server, client ->
            server.createContext("/panel/api/inbounds/addClient") { exchange ->
                exchange.requestBody.close()
                val bytes = """{"success":false,"msg":"Rejected client"}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            assertFailsWith<IOException> {
                client.addClient(4, XuiClientPayload(id = "test-id", email = "test-client"))
            }
        }
    }

    private fun withPanel(logins: AtomicInteger, block: (HttpServer, ThreeXuiClient) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/login") { exchange ->
            exchange.requestBody.close()
            exchange.responseHeaders.add("Set-Cookie", "session=${logins.incrementAndGet()}; Path=/")
            val bytes = """{"success":true}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(server, ThreeXuiClient(ThreeXuiConfig("http://127.0.0.1:${server.address.port}", "test", "test", 4, null)))
        } finally { server.stop(0) }
    }
}
