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
import io.ktor.server.routing.get
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

@Serializable
data class NodeClientInventoryResponse(
    val id: String,
    val nodeId: String,
    val inboundId: Int,
    val inboundRemark: String,
    val inboundTag: String,
    val email: String,
    val uuid: String?,
    val flow: String?,
    val enabled: Boolean,
    val totalBytes: Long,
    val upBytes: Long,
    val downBytes: Long,
    val expiryTime: Long,
    val limitIp: Int,
    val subId: String?,
    val lastSyncedAt: String
)

@Serializable
data class NodeClientsResponse(
    val nodeId: String,
    val clients: List<NodeClientInventoryResponse>
)

@Serializable
data class SyncNodeClientsResponse(
    val nodeId: String,
    val synced: Int,
    val clients: List<NodeClientInventoryResponse>
)

@Serializable
data class PatchNodeClientRequest(
    val totalGb: Long? = null,
    val totalBytes: Long? = null
)

@Serializable
data class PatchNodeClientResponse(
    val nodeId: String,
    val email: String,
    val totalBytes: Long
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

                    get("/{id}/clients") {
                        val principal = call.principal<JWTPrincipal>()
                            ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                        ensureAdmin(principal)

                        val nodeId = parseNodeId(call.parameters["id"])
                        context.nodes.findById(nodeId)
                            ?: throw ApiException(HttpStatusCode.NotFound, "NODE_NOT_FOUND", "Node not found")

                        call.respond(
                            NodeClientsResponse(
                                nodeId = nodeId.toString(),
                                clients = context.nodeClients.listByNode(nodeId).map(::toNodeClientResponse)
                            )
                        )
                    }

                    post("/{id}/clients/sync") {
                        val principal = call.principal<JWTPrincipal>()
                            ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                        ensureAdmin(principal)

                        val nodeId = parseNodeId(call.parameters["id"])
                        val node = context.nodes.findById(nodeId)
                            ?: throw ApiException(HttpStatusCode.NotFound, "NODE_NOT_FOUND", "Node not found")
                        val clients = context.xray.listClients(node)
                        context.nodeClients.upsertSnapshot(node.id, clients)
                        val stored = context.nodeClients.listByNode(node.id)

                        context.audit.log(
                            action = "node.clients.sync",
                            success = true,
                            actorUserId = null,
                            actorDeviceId = null,
                            targetType = "node",
                            targetId = node.id,
                            detailsJson = "{\"count\":${clients.size}}",
                            call = call
                        )

                        call.respond(
                            SyncNodeClientsResponse(
                                nodeId = node.id.toString(),
                                synced = clients.size,
                                clients = stored.map(::toNodeClientResponse)
                            )
                        )
                    }

                    patch("/{id}/clients/{email}") {
                        val principal = call.principal<JWTPrincipal>()
                            ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                        ensureAdmin(principal)

                        val nodeId = parseNodeId(call.parameters["id"])
                        val email = call.parameters["email"]?.takeIf { it.isNotBlank() }
                            ?: throw ApiException(HttpStatusCode.BadRequest, "INVALID_EMAIL", "Client email is required")
                        val node = context.nodes.findById(nodeId)
                            ?: throw ApiException(HttpStatusCode.NotFound, "NODE_NOT_FOUND", "Node not found")
                        val body = call.receive<PatchNodeClientRequest>()
                        val totalBytes = body.totalBytes
                            ?: body.totalGb?.let { gbToBytes(it) }
                            ?: throw ApiException(HttpStatusCode.BadRequest, "INVALID_LIMIT", "totalGb or totalBytes is required")
                        if (totalBytes < 0) {
                            throw ApiException(HttpStatusCode.BadRequest, "INVALID_LIMIT", "Traffic limit must be >= 0")
                        }

                        context.xray.updateClientTrafficLimit(node, email, totalBytes)
                        val clients = context.xray.listClients(node)
                        context.nodeClients.upsertSnapshot(node.id, clients)

                        context.audit.log(
                            action = "node.client.update_limit",
                            success = true,
                            actorUserId = null,
                            actorDeviceId = null,
                            targetType = "node",
                            targetId = node.id,
                            detailsJson = "{\"email\":\"${email.replace("\"", "\\\"")}\",\"totalBytes\":$totalBytes}",
                            call = call
                        )

                        call.respond(
                            PatchNodeClientResponse(
                                nodeId = node.id.toString(),
                                email = email,
                                totalBytes = totalBytes
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

private fun parseNodeId(raw: String?): UUID {
    val idRaw = raw ?: throw ApiException(HttpStatusCode.BadRequest, "INVALID_NODE_ID", "Node id is required")
    return runCatching { UUID.fromString(idRaw) }.getOrElse {
        throw ApiException(HttpStatusCode.BadRequest, "INVALID_NODE_ID", "Invalid node id")
    }
}

private fun toNodeClientResponse(client: com.example.db.NodeClientInventoryEntity): NodeClientInventoryResponse {
    return NodeClientInventoryResponse(
        id = client.id.toString(),
        nodeId = client.nodeId.toString(),
        inboundId = client.inboundId,
        inboundRemark = client.inboundRemark,
        inboundTag = client.inboundTag,
        email = client.email,
        uuid = client.uuid,
        flow = client.flow,
        enabled = client.enabled,
        totalBytes = client.totalBytes,
        upBytes = client.upBytes,
        downBytes = client.downBytes,
        expiryTime = client.expiryTime,
        limitIp = client.limitIp,
        subId = client.subId,
        lastSyncedAt = client.lastSyncedAt.toString()
    )
}

private fun ensureAdmin(principal: JWTPrincipal) {
    if (principal.requireRole() != "ADMIN") {
        throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "Admin role required")
    }
}

private fun bytesToGb(bytes: Long): Double = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)

private fun gbToBytes(gb: Long): Long = gb * 1024L * 1024L * 1024L
