package com.example.modules.vpn

import com.example.common.ApiException
import com.example.config.AppContext
import com.example.db.IssuedConfigEntity
import com.example.security.LimitRule
import com.example.security.deviceIdOrNull
import com.example.security.requireUserId
import com.example.xray.XrayUser
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.exception.DataAccessException
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Serializable
data class IssueConfigRequest(
    val deviceId: String,
    val region: String? = null,
    val ttlHours: Long? = null,
    val forceRotate: Boolean = false
)

@Serializable
data class RevokeConfigRequest(
    val configId: String,
    val reason: String = "user_request"
)

@Serializable
data class NodeRefResponse(
    val id: String,
    val name: String,
    val region: String
)

@Serializable
data class IssueConfigResponse(
    val configId: String,
    val status: String,
    val expiresAt: String,
    val issuedAt: String? = null,
    val revokedAt: String? = null,
    val node: NodeRefResponse,
    val vlessUri: String? = null,
    val clientConfig: JsonElement = JsonNull
)

@Serializable
data class RevokeConfigResponse(
    val configId: String,
    val status: String,
    val revokedAt: String?
)

fun Application.configureVpnRoutes(context: AppContext) {
    routing {
        authenticate("auth-jwt") {
            route("/vpn") {
                post("/issue") {
                    val principal = call.principal<JWTPrincipal>()
                        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                    val userId = principal.requireUserId()
                    val didFromToken = principal.deviceIdOrNull()
                        ?: throw ApiException(HttpStatusCode.Forbidden, "DEVICE_NOT_BOUND", "Token is not bound to device")

                    val idempotencyKey = call.request.headers["Idempotency-Key"]
                        ?: throw ApiException(HttpStatusCode.BadRequest, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header is required")

                    context.rateLimit.enforce(
                        key = "issue:$userId:$didFromToken",
                        rule = LimitRule(6, Duration.ofMinutes(1))
                    )

                    val body = call.receive<IssueConfigRequest>()
                    val deviceId = runCatching { UUID.fromString(body.deviceId) }.getOrElse {
                        throw ApiException(HttpStatusCode.BadRequest, "INVALID_DEVICE_ID", "Invalid deviceId")
                    }
                    if (deviceId != didFromToken) {
                        throw ApiException(HttpStatusCode.Forbidden, "DEVICE_BINDING_MISMATCH", "deviceId mismatch")
                    }

                    val device = context.devices.findByIdForUser(deviceId, userId)
                        ?: throw ApiException(HttpStatusCode.Forbidden, "DEVICE_NOT_BOUND", "Device not found")
                    if (device.status != "ENABLED") {
                        throw ApiException(HttpStatusCode.Forbidden, "DEVICE_DISABLED", "Device disabled")
                    }

                    val byKey = context.vpn.findByIdempotency(userId, idempotencyKey)
                    if (byKey != null) {
                        val node = context.nodes.findById(byKey.nodeId)
                            ?: throw ApiException(HttpStatusCode.InternalServerError, "NODE_NOT_FOUND", "Node missing for existing config")
                        call.respond(toIssueResponse(byKey, node.id.toString(), node.name, node.region))
                        return@post
                    }

                    if (!body.forceRotate) {
                        val active = context.vpn.findActiveByDevice(userId, deviceId)
                        if (active != null) {
                            val node = context.nodes.findById(active.nodeId)
                                ?: throw ApiException(HttpStatusCode.InternalServerError, "NODE_NOT_FOUND", "Node missing for active config")
                            call.respond(toIssueResponse(active, node.id.toString(), node.name, node.region))
                            return@post
                        }
                    }

                    val policy = context.policies.getCurrent(userId)
                        ?: throw ApiException(HttpStatusCode.NotFound, "POLICY_NOT_FOUND", "Policy not found")

                    val user = context.users.findById(userId)
                        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "User not found")
                    val quota = context.quota.current(userId, context.config.freeGbPerMonth)
                    if (quota.remainingBytes <= 0) {
                        if (user.accountType == "GUEST") {
                            throw ApiException(
                                HttpStatusCode.PaymentRequired,
                                "FREE_QUOTA_EXHAUSTED_REGISTER_REQUIRED",
                                "Free monthly quota exhausted. Register account and purchase additional GB."
                            )
                        }
                        throw ApiException(
                            HttpStatusCode.PaymentRequired,
                            "QUOTA_EXHAUSTED_PURCHASE_REQUIRED",
                            "Monthly quota exhausted. Purchase additional GB."
                        )
                    }

                    val node = context.nodes.pickNode(body.region)
                        ?: throw ApiException(HttpStatusCode.ServiceUnavailable, "NO_HEALTHY_NODES", "No healthy node available")

                    val ttl = body.ttlHours?.let { Duration.ofHours(it) } ?: context.config.issueConfigTtl
                    val expiresAt = Instant.now().plus(ttl)

                    val configId = try {
                        context.vpn.insertProvisioning(
                            userId = userId,
                            deviceId = deviceId,
                            nodeId = node.id,
                            idempotencyKey = idempotencyKey,
                            expiresAt = expiresAt
                        )
                    } catch (_: DataAccessException) {
                        throw ApiException(HttpStatusCode.Conflict, "ACTIVE_CONFIG_EXISTS", "Active config already exists")
                    }

                    val email = "u_${userId.toString().replace("-", "").take(8)}.d_${deviceId.toString().replace("-", "").take(8)}.c_${configId.toString().replace("-", "").take(8)}@cp.local"
                    val vlessUuid = UUID.randomUUID()
                    val flow = "xtls-rprx-vision"

                    var addedOnXray = false
                    try {
                        context.xray.addUser(
                            node = node,
                            user = XrayUser(
                                email = email,
                                uuid = vlessUuid,
                                flow = flow,
                                expiresAt = expiresAt
                            )
                        )
                        addedOnXray = true

                        val client = context.vpn.createClient(
                            userId = userId,
                            deviceId = deviceId,
                            nodeId = node.id,
                            email = email,
                            vlessUuid = vlessUuid,
                            flow = flow,
                            expiresAt = expiresAt
                        )

                        val rendered = VpnConfigRenderer.render(node, vlessUuid, flow, policy)
                        context.vpn.finalizeIssued(
                            configId = configId,
                            clientId = client.id,
                            vlessUri = rendered.vlessUri,
                            configJson = rendered.configJson,
                            configHash = rendered.hash,
                            issuedAt = Instant.now()
                        )

                        val issued = context.vpn.findConfigById(userId, configId)
                            ?: throw ApiException(HttpStatusCode.InternalServerError, "ISSUE_FAILED", "Issued config is missing")

                        context.audit.log(
                            action = "vpn.issue",
                            success = true,
                            actorUserId = userId,
                            actorDeviceId = deviceId,
                            targetType = "issued_config",
                            targetId = configId,
                            detailsJson = buildJsonObject {
                                put("nodeId", node.id.toString())
                            }.toString(),
                            call = call
                        )

                        call.respond(toIssueResponse(issued, node.id.toString(), node.name, node.region))
                    } catch (e: Exception) {
                        if (addedOnXray) {
                            runCatching { context.xray.removeUser(node, email) }
                        }
                        context.vpn.markFailed(configId, "XRAY_ADD_FAILED", e.message ?: "xray add failed")
                        throw ApiException(HttpStatusCode.BadGateway, "XRAY_ADD_FAILED", "Failed to add user on Xray")
                    }
                }

                post("/revoke") {
                    val principal = call.principal<JWTPrincipal>()
                        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                    val userId = principal.requireUserId()
                    val didFromToken = principal.deviceIdOrNull()
                        ?: throw ApiException(HttpStatusCode.Forbidden, "DEVICE_NOT_BOUND", "Token is not bound to device")

                    val body = call.receive<RevokeConfigRequest>()
                    val configId = runCatching { UUID.fromString(body.configId) }.getOrElse {
                        throw ApiException(HttpStatusCode.BadRequest, "INVALID_CONFIG_ID", "Invalid configId")
                    }

                    val config = context.vpn.findConfigById(userId, configId)
                        ?: throw ApiException(HttpStatusCode.NotFound, "CONFIG_NOT_FOUND", "Config not found")

                    if (config.deviceId != didFromToken) {
                        throw ApiException(HttpStatusCode.Forbidden, "DEVICE_BINDING_MISMATCH", "Config belongs to another device")
                    }

                    if (config.status == "REVOKED") {
                        call.respond(
                            RevokeConfigResponse(
                                configId = config.id.toString(),
                                status = config.status,
                                revokedAt = config.revokedAt?.toString()
                            )
                        )
                        return@post
                    }

                    if (config.clientId == null) {
                        context.vpn.markRevoked(config.id)
                        val after = context.vpn.findConfigById(userId, config.id)!!
                        call.respond(
                            RevokeConfigResponse(
                                configId = after.id.toString(),
                                status = after.status,
                                revokedAt = after.revokedAt?.toString()
                            )
                        )
                        return@post
                    }

                    val client = context.vpn.findClientById(config.clientId)
                        ?: throw ApiException(HttpStatusCode.InternalServerError, "CLIENT_NOT_FOUND", "Client record missing")
                    val node = context.nodes.findById(config.nodeId)
                        ?: throw ApiException(HttpStatusCode.InternalServerError, "NODE_NOT_FOUND", "Node record missing")

                    context.vpn.markRevoking(config.id)
                    try {
                        context.xray.removeUser(node, client.email)
                        context.vpn.revokeClient(client.id, body.reason)
                        context.vpn.markRevoked(config.id)

                        context.audit.log(
                            action = "vpn.revoke",
                            success = true,
                            actorUserId = userId,
                            actorDeviceId = didFromToken,
                            targetType = "issued_config",
                            targetId = config.id,
                            detailsJson = "{\"reason\":\"${body.reason}\"}",
                            call = call
                        )
                    } catch (e: Exception) {
                        throw ApiException(HttpStatusCode.BadGateway, "XRAY_REMOVE_FAILED", "Failed to remove user on Xray")
                    }

                    val after = context.vpn.findConfigById(userId, config.id)
                        ?: throw ApiException(HttpStatusCode.InternalServerError, "CONFIG_NOT_FOUND", "Config missing")
                    call.respond(
                        RevokeConfigResponse(
                            configId = after.id.toString(),
                            status = after.status,
                            revokedAt = after.revokedAt?.toString()
                        )
                    )
                }
            }
        }
    }
}

private fun toIssueResponse(config: IssuedConfigEntity, nodeId: String, nodeName: String, region: String): IssueConfigResponse {
    val cfg = config.configJson?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull
    return IssueConfigResponse(
        configId = config.id.toString(),
        status = config.status,
        expiresAt = config.expiresAt.toString(),
        issuedAt = config.issuedAt?.toString(),
        revokedAt = config.revokedAt?.toString(),
        node = NodeRefResponse(
            id = nodeId,
            name = nodeName,
            region = region
        ),
        vlessUri = config.vlessUri,
        clientConfig = cfg
    )
}
