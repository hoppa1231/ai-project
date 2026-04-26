package com.example.modules.admin

import com.example.common.ApiException
import com.example.config.AppContext
import com.example.db.repo.CreateNodeParams
import com.example.db.repo.UpdateNodeParams
import com.example.security.requireRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.jooq.exception.DataAccessException
import java.util.UUID

@Serializable
data class AdminNodeResponse(
    val id: String,
    val name: String,
    val region: String,
    val status: String,
    val health: String,
    val weight: Int,
    val load: Int,
    val maxClients: Int
)

@Serializable
data class CreateNodeRequest(
    val name: String,
    val region: String,
    val countryCode: String,
    val hostname: String,
    val publicAddress: String,
    val publicPort: Int,
    val apiHost: String,
    val apiPort: Int,
    val inboundTag: String,
    val realityServerName: String,
    val realityPublicKey: String,
    val realityShortId: String = "",
    val realityFingerprint: String = "chrome",
    val realityAlpn: List<String> = listOf("h2", "http/1.1"),
    val weight: Int = 100,
    val maxClients: Int = 10000
)

@Serializable
data class PatchNodeRequest(
    val status: String? = null,
    val weight: Int? = null,
    val maxClients: Int? = null
)

@Serializable
data class GrantQuotaRequest(
    val userId: String? = null,
    val email: String? = null,
    val gb: Long,
    val source: String = "purchase",
    val externalRef: String? = null
)

@Serializable
data class GrantQuotaResponse(
    val userId: String,
    val accountType: String,
    val cycleStart: String,
    val cycleEnd: String,
    val grantedGb: Long,
    val freeGb: Double,
    val purchasedGb: Double,
    val usedGb: Double,
    val remainingGb: Double
)

fun Application.configureAdminRoutes(context: AppContext) {
    routing {
        authenticate("auth-jwt") {
            route("/admin") {
                route("/nodes") {
                    post {
                        val principal = call.principal<JWTPrincipal>()
                            ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                        ensureAdmin(principal)

                        val body = call.receive<CreateNodeRequest>()
                        val node = try {
                            context.nodes.create(
                                CreateNodeParams(
                                    name = body.name,
                                    region = body.region,
                                    countryCode = body.countryCode,
                                    hostname = body.hostname,
                                    publicAddress = body.publicAddress,
                                    publicPort = body.publicPort,
                                    apiHost = body.apiHost,
                                    apiPort = body.apiPort,
                                    inboundTag = body.inboundTag,
                                    realityServerName = body.realityServerName,
                                    realityPublicKey = body.realityPublicKey,
                                    realityShortId = body.realityShortId,
                                    realityFingerprint = body.realityFingerprint,
                                    realityAlpn = body.realityAlpn,
                                    weight = body.weight,
                                    maxClients = body.maxClients
                                )
                            )
                        } catch (_: DataAccessException) {
                            throw ApiException(HttpStatusCode.Conflict, "NODE_CONFLICT", "Node with same unique parameters already exists")
                        }

                        call.respond(
                            HttpStatusCode.Created,
                            AdminNodeResponse(
                                id = node.id.toString(),
                                name = node.name,
                                region = node.region,
                                status = node.status,
                                health = node.health,
                                weight = node.weight,
                                load = node.load,
                                maxClients = node.maxClients
                            )
                        )
                    }

                    patch("/{id}") {
                        val principal = call.principal<JWTPrincipal>()
                            ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                        ensureAdmin(principal)

                        val idRaw = call.parameters["id"]
                            ?: throw ApiException(HttpStatusCode.BadRequest, "INVALID_NODE_ID", "Node id is required")
                        val nodeId = runCatching { UUID.fromString(idRaw) }.getOrElse {
                            throw ApiException(HttpStatusCode.BadRequest, "INVALID_NODE_ID", "Invalid node id")
                        }

                        val body = call.receive<PatchNodeRequest>()
                        val node = context.nodes.update(
                            id = nodeId,
                            params = UpdateNodeParams(
                                status = body.status?.uppercase(),
                                weight = body.weight,
                                maxClients = body.maxClients
                            )
                        ) ?: throw ApiException(HttpStatusCode.NotFound, "NODE_NOT_FOUND", "Node not found")

                        call.respond(
                            AdminNodeResponse(
                                id = node.id.toString(),
                                name = node.name,
                                region = node.region,
                                status = node.status,
                                health = node.health,
                                weight = node.weight,
                                load = node.load,
                                maxClients = node.maxClients
                            )
                        )
                    }
                }

                route("/quota") {
                    post("/grant") {
                        val principal = call.principal<JWTPrincipal>()
                            ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                        ensureAdmin(principal)

                        val body = call.receive<GrantQuotaRequest>()
                        if (body.gb <= 0) {
                            throw ApiException(HttpStatusCode.BadRequest, "INVALID_GB", "gb must be positive")
                        }

                        val user = when {
                            body.userId != null -> {
                                val uid = runCatching { UUID.fromString(body.userId) }.getOrElse {
                                    throw ApiException(HttpStatusCode.BadRequest, "INVALID_USER_ID", "Invalid userId")
                                }
                                context.users.findById(uid)
                            }
                            body.email != null -> context.users.findByEmail(body.email)
                            else -> null
                        } ?: throw ApiException(HttpStatusCode.NotFound, "USER_NOT_FOUND", "User not found")

                        if (user.accountType != "REGISTERED") {
                            throw ApiException(
                                HttpStatusCode.Conflict,
                                "REGISTRATION_REQUIRED",
                                "Guest user must be upgraded before purchased quota"
                            )
                        }

                        val quota = try {
                            context.quota.grantPurchased(
                                userId = user.id,
                                gb = body.gb,
                                source = body.source,
                                externalRef = body.externalRef,
                                freeGbPerMonth = context.config.freeGbPerMonth
                            )
                        } catch (_: DataAccessException) {
                            throw ApiException(HttpStatusCode.Conflict, "DUPLICATE_EXTERNAL_REF", "externalRef already used")
                        }

                        context.audit.log(
                            action = "quota.grant",
                            success = true,
                            actorUserId = null,
                            actorDeviceId = null,
                            targetType = "user",
                            targetId = user.id,
                            detailsJson = "{\"gb\":${body.gb},\"source\":\"${body.source}\"}",
                            call = call
                        )

                        call.respond(
                            GrantQuotaResponse(
                                userId = user.id.toString(),
                                accountType = user.accountType,
                                cycleStart = quota.cycleStart.toString(),
                                cycleEnd = quota.cycleEnd.toString(),
                                grantedGb = body.gb,
                                freeGb = bytesToGb(quota.freeBytes),
                                purchasedGb = bytesToGb(quota.purchasedBytes),
                                usedGb = bytesToGb(quota.usedBytes),
                                remainingGb = bytesToGb(quota.remainingBytes)
                            )
                        )
                    }
                }
            }
        }
    }
}

private fun ensureAdmin(principal: JWTPrincipal) {
    if (principal.requireRole() != "ADMIN") {
        throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "Admin role required")
    }
}

private fun bytesToGb(bytes: Long): Double = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
