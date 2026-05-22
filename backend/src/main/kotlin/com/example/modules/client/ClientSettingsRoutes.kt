package com.example.modules.client

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
data class ClientSettingsResponse(
    val defaultRouteMode: String,
    val cascadeEnabled: Boolean,
    val cascadeFallbackToSingle: Boolean,
    val clientConfigPreferred: Boolean
)

fun Application.configureClientSettingsRoutes(context: AppContext) {
    routing {
        authenticate("auth-jwt") {
            get("/client/settings") {
                val principal = call.principal<JWTPrincipal>()
                    ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                if (principal.payload.subject.isNullOrBlank()) {
                    throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid token")
                }

                val routeMode = context.config.defaultVpnRouteMode
                call.respond(
                    ClientSettingsResponse(
                        defaultRouteMode = routeMode,
                        cascadeEnabled = routeMode == "CASCADE",
                        cascadeFallbackToSingle = context.config.cascadeFallbackToSingle,
                        clientConfigPreferred = true
                    )
                )
            }
        }
    }
}
