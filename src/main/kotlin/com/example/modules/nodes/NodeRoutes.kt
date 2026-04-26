package com.example.modules.nodes

import com.example.common.ApiException
import com.example.config.AppContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class NodeResponse(
    val id: String,
    val name: String,
    val region: String,
    val countryCode: String,
    val hostname: String,
    val port: Int,
    val health: String,
    val load: Int,
    val fingerprint: String,
    val alpn: List<String>
)

fun Application.configureNodeRoutes(context: AppContext) {
    routing {
        authenticate("auth-jwt") {
            get("/nodes") {
                val principal = call.principal<JWTPrincipal>()
                    ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                if (principal.payload.subject.isNullOrBlank()) {
                    throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid token")
                }

                val region = call.request.queryParameters["region"]
                val nodes = context.nodes.listPublic(region).map {
                    NodeResponse(
                        id = it.id.toString(),
                        name = it.name,
                        region = it.region,
                        countryCode = it.countryCode,
                        hostname = it.hostname,
                        port = it.port,
                        health = it.health,
                        load = it.load,
                        fingerprint = it.fingerprint,
                        alpn = it.alpn
                    )
                }
                call.respond(nodes)
            }
        }
    }
}
