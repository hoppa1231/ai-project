package com.example.common

import com.example.configureRequestCorrelation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.testing.testApplication
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RequestIdTest {
    @Test
    fun `response echoes a valid client request id`() = testApplication {
        application {
            configureRequestCorrelation()
            routing { get("/health") { call.respondText("ok") } }
        }

        val response = client.get("/health") {
            header("X-Request-Id", "portfolio-test-123")
        }

        assertEquals("portfolio-test-123", response.headers["X-Request-Id"])
    }

    @Test
    fun `response generates a request id when client omits it`() = testApplication {
        application {
            configureRequestCorrelation()
            routing { get("/health") { call.respondText("ok") } }
        }

        val response = client.get("/health")

        assertNotNull(response.headers["X-Request-Id"])
    }
}
