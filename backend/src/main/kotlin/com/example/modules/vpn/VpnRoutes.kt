package com.example.modules.vpn

import com.example.common.ApiException
import com.example.config.AppContext
import com.example.db.ClientEntity
import com.example.db.IssuedConfigEntity
import com.example.db.IssuedConfigHopEntity
import com.example.db.NodeClientInventoryEntity
import com.example.db.NodeEntity
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
import io.ktor.server.routing.get
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
    val exitRegion: String? = null,
    val exitNodeId: String? = null,
    val routeMode: String? = null,
    val mode: String? = null,
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
    val routeMode: String = "SINGLE",
    val node: NodeRefResponse,
    val entryNode: NodeRefResponse? = null,
    val exitNode: NodeRefResponse? = null,
    val hops: List<ConfigHopResponse> = emptyList(),
    val vlessUri: String? = null,
    val clientConfig: JsonElement = JsonNull
)

@Serializable
data class ConfigHopResponse(
    val index: Int,
    val role: String,
    val node: NodeRefResponse,
    val vlessUri: String? = null
)

@Serializable
data class RevokeConfigResponse(
    val configId: String,
    val status: String,
    val revokedAt: String?
)

@Serializable
data class VpnConfigsResponse(
    val configs: List<IssueConfigResponse>
)

@Serializable
data class TelegramVpnConfigResponse(
    val nodeId: String,
    val nodeName: String,
    val region: String,
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
    val subId: String?,
    val vlessUri: String?,
    val lastSyncedAt: String
)

@Serializable
data class TelegramVpnConfigsResponse(
    val telegramId: Long,
    val configs: List<TelegramVpnConfigResponse>
)

fun Application.configureVpnRoutes(context: AppContext) {
    routing {
        authenticate("auth-jwt") {
            route("/vpn") {
                get("/configs") {
                    val principal = call.principal<JWTPrincipal>()
                        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                    val userId = principal.requireUserId()
                    val didFromToken = principal.deviceIdOrNull()
                    val requestedDeviceId = call.request.queryParameters["deviceId"]?.let {
                        runCatching { UUID.fromString(it) }.getOrElse {
                            throw ApiException(HttpStatusCode.BadRequest, "INVALID_DEVICE_ID", "Invalid deviceId")
                        }
                    }

                    if (requestedDeviceId != null && didFromToken != null && requestedDeviceId != didFromToken) {
                        throw ApiException(HttpStatusCode.Forbidden, "DEVICE_BINDING_MISMATCH", "deviceId mismatch")
                    }

                    val configs = context.vpn.listConfigs(userId, requestedDeviceId ?: didFromToken)
                    call.respond(
                        VpnConfigsResponse(
                            configs = configs.map { config -> buildIssueResponse(context, config) }
                        )
                    )
                }

                get("/telegram-configs") {
                    val principal = call.principal<JWTPrincipal>()
                        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                    val userId = principal.requireUserId()
                    val telegramId = context.users.getTelegramId(userId)
                        ?: throw ApiException(HttpStatusCode.Forbidden, "TELEGRAM_NOT_LINKED", "Telegram account is not linked")

                    val clients = context.nodeClients.listByTelegramId(telegramId)
                    val nodes = clients.map { it.nodeId }.distinct().associateWith { nodeId ->
                        context.nodes.findById(nodeId)
                    }

                    call.respond(
                        TelegramVpnConfigsResponse(
                            telegramId = telegramId,
                            configs = clients.mapNotNull { client ->
                                val node = nodes[client.nodeId] ?: return@mapNotNull null
                                toTelegramConfigResponse(client, node)
                            }
                        )
                    )
                }

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
                        rule = LimitRule(30, Duration.ofMinutes(1))
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
                        call.respond(buildIssueResponse(context, byKey))
                        return@post
                    }

                    if (!body.forceRotate) {
                        val active = context.vpn.findActiveByDevice(userId, deviceId)
                        if (active != null) {
                            call.respond(buildIssueResponse(context, active))
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

                    val routeMode = normalizeRouteMode(body.routeMode ?: body.mode ?: "CASCADE")
                    val exitRegion = body.exitRegion ?: body.region
                    val exitNode = body.exitNodeId?.takeIf { it.isNotBlank() }?.let { rawId ->
                        val nodeId = runCatching { UUID.fromString(rawId) }.getOrElse {
                            throw ApiException(HttpStatusCode.BadRequest, "INVALID_EXIT_NODE_ID", "Invalid exitNodeId")
                        }
                        context.nodes.findHealthyById(nodeId)
                            ?: throw ApiException(HttpStatusCode.ServiceUnavailable, "EXIT_NODE_UNAVAILABLE", "Selected exit node is unavailable")
                    } ?: (context.nodes.pickNode(exitRegion)
                        ?: throw ApiException(HttpStatusCode.ServiceUnavailable, "NO_HEALTHY_NODES", "No healthy exit node available"))

                    val hopSpecs = if (routeMode == "CASCADE") {
                        val entryNode = context.nodes.pickEntryNode(exitNode)
                            ?: throw ApiException(
                                HttpStatusCode.ServiceUnavailable,
                                "NO_ENTRY_NODES",
                                "No healthy entry node available for cascade route"
                            )
                        listOf(
                            HopSpec(index = 0, role = "ENTRY", node = entryNode),
                            HopSpec(index = 1, role = "EXIT", node = exitNode)
                        )
                    } else {
                        listOf(HopSpec(index = 0, role = "EXIT", node = exitNode))
                    }

                    val ttl = body.ttlHours?.let { Duration.ofHours(it) } ?: context.config.issueConfigTtl
                    val expiresAt = Instant.now().plus(ttl)
                    val telegramId = context.users.getTelegramId(userId)

                    val configId = try {
                        context.vpn.insertProvisioning(
                            userId = userId,
                            deviceId = deviceId,
                            nodeId = exitNode.id,
                            idempotencyKey = idempotencyKey,
                            expiresAt = expiresAt,
                            routeMode = routeMode
                        )
                    } catch (_: DataAccessException) {
                        throw ApiException(HttpStatusCode.Conflict, "ACTIVE_CONFIG_EXISTS", "Active config already exists")
                    }

                    val flow = "xtls-rprx-vision"
                    val addedUsers = mutableListOf<Pair<NodeEntity, String>>()
                    val createdClients = mutableListOf<ClientEntity>()
                    val provisionedHops = mutableListOf<ProvisionedHop>()
                    try {
                        hopSpecs.forEach { spec ->
                            val email = clientEmail(userId, deviceId, configId, spec.role, spec.index)
                            val vlessUuid = UUID.randomUUID()
                            context.xray.addUser(
                                node = spec.node,
                                user = XrayUser(
                                    email = email,
                                    uuid = vlessUuid,
                                    flow = flow,
                                    expiresAt = expiresAt,
                                    telegramId = telegramId
                                )
                            )
                            addedUsers += spec.node to email

                            val client = context.vpn.createClient(
                                userId = userId,
                                deviceId = deviceId,
                                nodeId = spec.node.id,
                                email = email,
                                vlessUuid = vlessUuid,
                                flow = flow,
                                expiresAt = expiresAt
                            )
                            createdClients += client

                            context.vpn.createConfigHop(
                                configId = configId,
                                hopIndex = spec.index,
                                role = spec.role,
                                nodeId = spec.node.id,
                                clientId = client.id,
                                vlessUuid = vlessUuid,
                                flow = flow
                            )
                            provisionedHops += ProvisionedHop(spec, client, vlessUuid, flow)
                        }

                        val rendered = if (routeMode == "CASCADE") {
                            VpnConfigRenderer.renderCascade(
                                provisionedHops.map { hop ->
                                    VpnRouteHop(
                                        index = hop.spec.index,
                                        role = hop.spec.role,
                                        node = hop.spec.node,
                                        vlessUuid = hop.vlessUuid,
                                        flow = hop.flow
                                    )
                                },
                                policy
                            )
                        } else {
                            val hop = provisionedHops.single()
                            VpnConfigRenderer.render(hop.spec.node, hop.vlessUuid, hop.flow, policy)
                        }
                        val primaryClient = provisionedHops.last().client
                        context.vpn.finalizeIssued(
                            configId = configId,
                            clientId = primaryClient.id,
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
                                put("routeMode", routeMode)
                                put("exitNodeId", exitNode.id.toString())
                                provisionedHops.firstOrNull { it.spec.role == "ENTRY" }?.let {
                                    put("entryNodeId", it.spec.node.id.toString())
                                }
                            }.toString(),
                            call = call
                        )

                        call.respond(buildIssueResponse(context, issued))
                    } catch (e: Exception) {
                        addedUsers.asReversed().forEach { (node, email) ->
                            runCatching { context.xray.removeUser(node, email) }
                        }
                        createdClients.forEach { client ->
                            runCatching { context.vpn.revokeClient(client.id, "issue_failed") }
                        }
                        context.vpn.markConfigHopsFailed(configId)
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

                    val hops = context.vpn.listConfigHops(config.id)
                    context.vpn.markRevoking(config.id)
                    if (hops.isNotEmpty()) {
                        try {
                            hops.sortedByDescending { it.hopIndex }.forEach { hop ->
                                val clientId = hop.clientId
                                    ?: throw ApiException(HttpStatusCode.InternalServerError, "CLIENT_NOT_FOUND", "Hop client is missing")
                                val client = context.vpn.findClientById(clientId)
                                    ?: throw ApiException(HttpStatusCode.InternalServerError, "CLIENT_NOT_FOUND", "Client record missing")
                                val node = context.nodes.findById(hop.nodeId)
                                    ?: throw ApiException(HttpStatusCode.InternalServerError, "NODE_NOT_FOUND", "Node record missing")
                                context.xray.removeUser(node, client.email)
                                context.vpn.revokeClient(client.id, body.reason)
                            }
                            context.vpn.markConfigHopsRevoked(config.id)
                            context.vpn.markRevoked(config.id)

                            context.audit.log(
                                action = "vpn.revoke",
                                success = true,
                                actorUserId = userId,
                                actorDeviceId = didFromToken,
                                targetType = "issued_config",
                                targetId = config.id,
                                detailsJson = buildJsonObject {
                                    put("reason", body.reason)
                                    put("routeMode", config.routeMode)
                                    put("hopCount", hops.size)
                                }.toString(),
                                call = call
                            )
                        } catch (e: Exception) {
                            throw ApiException(HttpStatusCode.BadGateway, "XRAY_REMOVE_FAILED", "Failed to remove user on Xray")
                        }
                    } else if (config.clientId == null) {
                        context.vpn.markRevoked(config.id)
                    } else {
                        val client = context.vpn.findClientById(config.clientId)
                            ?: throw ApiException(HttpStatusCode.InternalServerError, "CLIENT_NOT_FOUND", "Client record missing")
                        val node = context.nodes.findById(config.nodeId)
                            ?: throw ApiException(HttpStatusCode.InternalServerError, "NODE_NOT_FOUND", "Node record missing")

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
                                detailsJson = buildJsonObject {
                                    put("reason", body.reason)
                                    put("routeMode", config.routeMode)
                                }.toString(),
                                call = call
                            )
                        } catch (e: Exception) {
                            throw ApiException(HttpStatusCode.BadGateway, "XRAY_REMOVE_FAILED", "Failed to remove user on Xray")
                        }
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

private data class HopSpec(
    val index: Int,
    val role: String,
    val node: NodeEntity
)

private data class ProvisionedHop(
    val spec: HopSpec,
    val client: ClientEntity,
    val vlessUuid: UUID,
    val flow: String?
)

private fun normalizeRouteMode(raw: String): String {
    val normalized = raw.trim().uppercase()
    if (normalized == "SINGLE" || normalized == "CASCADE") return normalized
    throw ApiException(HttpStatusCode.BadRequest, "INVALID_ROUTE_MODE", "routeMode must be SINGLE or CASCADE")
}

private fun clientEmail(userId: UUID, deviceId: UUID, configId: UUID, role: String, hopIndex: Int): String {
    val userPart = userId.toString().replace("-", "").take(8)
    val devicePart = deviceId.toString().replace("-", "").take(8)
    val configPart = configId.toString().replace("-", "").take(8)
    return "u_$userPart.d_$devicePart.c_$configPart.h_${hopIndex}_${role.lowercase()}@cp.local"
}

private fun buildIssueResponse(context: AppContext, config: IssuedConfigEntity): IssueConfigResponse {
    val primaryNode = context.nodes.findById(config.nodeId)
        ?: throw ApiException(HttpStatusCode.InternalServerError, "NODE_NOT_FOUND", "Node record missing")
    val hops = context.vpn.listConfigHops(config.id)
    val hopNodes = hops.map { it.nodeId }.distinct().associateWith { nodeId ->
        context.nodes.findById(nodeId)
            ?: throw ApiException(HttpStatusCode.InternalServerError, "NODE_NOT_FOUND", "Hop node record missing")
    }
    return toIssueResponse(config, primaryNode, hops, hopNodes)
}

private fun toIssueResponse(
    config: IssuedConfigEntity,
    primaryNode: NodeEntity,
    hops: List<IssuedConfigHopEntity>,
    hopNodes: Map<UUID, NodeEntity>
): IssueConfigResponse {
    val cfg = config.configJson?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull
    val primaryNodeRef = primaryNode.toNodeRef()
    val hopResponses = hops.map { hop ->
        val node = hopNodes.getValue(hop.nodeId)
        ConfigHopResponse(
            index = hop.hopIndex,
            role = hop.role,
            node = node.toNodeRef(),
            vlessUri = VpnConfigRenderer.vlessUri(node, hop.vlessUuid, hop.flow)
        )
    }
    val entryNode = hopResponses.firstOrNull { it.role == "ENTRY" }?.node
    val exitNode = hopResponses.lastOrNull { it.role == "EXIT" }?.node ?: primaryNodeRef

    return IssueConfigResponse(
        configId = config.id.toString(),
        status = config.status,
        expiresAt = config.expiresAt.toString(),
        issuedAt = config.issuedAt?.toString(),
        revokedAt = config.revokedAt?.toString(),
        routeMode = config.routeMode,
        node = exitNode,
        entryNode = entryNode,
        exitNode = exitNode,
        hops = hopResponses,
        vlessUri = config.vlessUri,
        clientConfig = cfg
    )
}

private fun toTelegramConfigResponse(
    client: NodeClientInventoryEntity,
    node: NodeEntity
): TelegramVpnConfigResponse {
    val vlessUri = client.uuid?.let { rawUuid ->
        runCatching {
            VpnConfigRenderer.vlessUri(node, UUID.fromString(rawUuid), client.flow)
        }.getOrNull()
    }

    return TelegramVpnConfigResponse(
        nodeId = node.id.toString(),
        nodeName = node.name,
        region = node.region,
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
        subId = client.subId,
        vlessUri = vlessUri,
        lastSyncedAt = client.lastSyncedAt.toString()
    )
}

private fun NodeEntity.toNodeRef(): NodeRefResponse {
    return NodeRefResponse(
        id = id.toString(),
        name = name,
        region = region
    )
}
