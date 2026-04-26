package com.example.modules.health

import com.example.common.ApiException
import com.example.config.AppContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable


@Serializable
data class HealthResponse(
    val status: String,
    val db: String,
    val healthyNodes: Int,
    val totalNodes: Int
)

fun Application.configureHealthRoutes(context: AppContext) {
    routing {
        get("/health") {
            val dbOk = try {
                context.db.dsl.fetchOne("SELECT 1") != null
            } catch (_: Exception) {
                false
            }

            val nodes = context.nodes.listAll()
            val healthy = nodes.count { it.health == "HEALTHY" }

            if (!dbOk) {
                throw ApiException(HttpStatusCode.ServiceUnavailable, "DB_UNHEALTHY", "Database is not reachable")
            }

            call.respond(
                HealthResponse(
                    status = "ok",
                    db = "ok",
                    healthyNodes = healthy,
                    totalNodes = nodes.size
                )
            )
        }
    }
}
