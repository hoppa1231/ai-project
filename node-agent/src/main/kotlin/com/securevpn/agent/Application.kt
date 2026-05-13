package com.securevpn.agent

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

fun Application.module() {
    val config = AgentConfig.load(this)
    val xui = ThreeXuiClient(config.xui)

    install(CallLogging)
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(StatusPages) {
        exception<UnauthorizedException> { call, cause ->
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("UNAUTHORIZED", cause.message ?: "Unauthorized"))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", cause.message ?: "Bad request"))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Node agent request failed", cause)
            call.respond(HttpStatusCode.BadGateway, ErrorResponse("THREE_XUI_ERROR", cause.message ?: "3x-ui error"))
        }
    }

    routing {
        get("/v1/health") {
            call.requireAgentToken(config.agentToken)
            val status = xui.status()
            call.respond(HealthResponse(ok = true, xui = status))
        }

        get("/v1/clients") {
            call.requireAgentToken(config.agentToken)
            val inboundTag = call.request.queryParameters["inboundTag"]
            val inboundId = inboundTag?.let { config.inboundIdFor(it) }
            call.respond(ClientsResponse(clients = xui.listClients(inboundId)))
        }

        post("/v1/users/add") {
            call.requireAgentToken(config.agentToken)
            val body = call.receive<AddUserRequest>()
            val inboundId = config.inboundIdFor(body.inboundTag)
            xui.addClient(
                inboundId = inboundId,
                client = XuiClientPayload(
                    id = body.uuid,
                    email = body.email,
                    flow = body.flow.orEmpty(),
                    expiryTime = body.expiresAt?.let { Instant.parse(it).toEpochMilli() } ?: 0L,
                    tgId = body.tgId.orEmpty()
                )
            )
            call.respond(ProvisionResponse(status = "ACTIVE", inboundId = inboundId, email = body.email))
        }

        post("/v1/users/remove") {
            call.requireAgentToken(config.agentToken)
            val body = call.receive<RemoveUserRequest>()
            val inboundId = config.inboundIdFor(body.inboundTag)
            xui.deleteClientByEmail(inboundId = inboundId, email = body.email)
            call.respond(ProvisionResponse(status = "REVOKED", inboundId = inboundId, email = body.email))
        }

        post("/v1/users/update") {
            call.requireAgentToken(config.agentToken)
            val body = call.receive<UpdateUserRequest>()
            val inboundId = config.inboundIdFor(body.inboundTag)
            xui.updateClientTrafficLimit(
                inboundId = inboundId,
                email = body.email,
                totalBytes = body.totalBytes
            )
            call.respond(UpdateUserResponse(status = "UPDATED", inboundId = inboundId, email = body.email))
        }
    }
}

private fun ApplicationCall.requireAgentToken(expected: String) {
    val actual = request.header("Authorization")?.removePrefix("Bearer ")?.trim()
    if (actual.isNullOrBlank() || actual != expected) {
        throw UnauthorizedException("Invalid agent token")
    }
}

@Serializable
data class AddUserRequest(
    val inboundTag: String? = null,
    val email: String,
    val uuid: String,
    val flow: String? = null,
    val expiresAt: String? = null,
    val tgId: String? = null
)

@Serializable
data class RemoveUserRequest(
    val inboundTag: String? = null,
    val email: String
)

@Serializable
data class UpdateUserRequest(
    val inboundTag: String? = null,
    val email: String,
    val totalBytes: Long
)

@Serializable
data class ProvisionResponse(
    val status: String,
    val inboundId: Int,
    val email: String
)

@Serializable
data class UpdateUserResponse(
    val status: String,
    val inboundId: Int,
    val email: String
)

@Serializable
data class ClientsResponse(
    val clients: List<XuiClientSnapshot>
)

@Serializable
data class HealthResponse(
    val ok: Boolean,
    val xui: String
)

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String
)

private class UnauthorizedException(message: String) : RuntimeException(message)
