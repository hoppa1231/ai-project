package com.example.modules.admin

import com.example.common.ApiException
import com.example.config.AppContext
import com.example.db.NodeEntity
import com.example.db.repo.CreateNodeParams
import com.example.db.repo.UpdateNodeParams
import com.example.security.requireRole
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
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
    val countryCode: String,
    val hostname: String,
    val publicAddress: String,
    val publicPort: Int,
    val apiHost: String,
    val apiPort: Int,
    val inboundTag: String,
    val realityServerName: String,
    val realityPublicKey: String,
    val realityShortId: String,
    val realityFingerprint: String,
    val realityAlpn: List<String>,
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
    val name: String? = null,
    val region: String? = null,
    val countryCode: String? = null,
    val hostname: String? = null,
    val publicAddress: String? = null,
    val publicPort: Int? = null,
    val apiHost: String? = null,
    val apiPort: Int? = null,
    val inboundTag: String? = null,
    val realityServerName: String? = null,
    val realityPublicKey: String? = null,
    val realityShortId: String? = null,
    val realityFingerprint: String? = null,
    val realityAlpn: List<String>? = null,
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
    val telegramId: Long?,
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
        get("/admin") {
            call.respondText(adminPanelHtml, ContentType.Text.Html)
        }

        authenticate("auth-jwt") {
            route("/admin") {
                route("/nodes") {
                    get {
                        val principal = call.principal<JWTPrincipal>()
                            ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                        ensureAdmin(principal)

                        call.respond(
                            context.nodes.listAll().map(::toAdminNodeResponse)
                        )
                    }

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
                            toAdminNodeResponse(node)
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
                        val node = try {
                            context.nodes.update(
                                id = nodeId,
                                params = UpdateNodeParams(
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
                                    status = body.status?.uppercase(),
                                    weight = body.weight,
                                    maxClients = body.maxClients
                                )
                            )
                        } catch (_: DataAccessException) {
                            throw ApiException(HttpStatusCode.Conflict, "NODE_CONFLICT", "Node with same unique parameters already exists")
                        } ?: throw ApiException(HttpStatusCode.NotFound, "NODE_NOT_FOUND", "Node not found")

                        call.respond(toAdminNodeResponse(node))
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

                    delete("/{id}/clients/{email}") {
                        val principal = call.principal<JWTPrincipal>()
                            ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                        ensureAdmin(principal)

                        val nodeId = parseNodeId(call.parameters["id"])
                        val email = call.parameters["email"]?.takeIf { it.isNotBlank() }
                            ?: throw ApiException(HttpStatusCode.BadRequest, "INVALID_EMAIL", "Client email is required")
                        val node = context.nodes.findById(nodeId)
                            ?: throw ApiException(HttpStatusCode.NotFound, "NODE_NOT_FOUND", "Node not found")

                        context.xray.removeUser(node, email)
                        context.nodeClients.deleteByNodeAndEmail(node.id, email)
                        val remainingClients = context.nodeClients.listByNode(node.id)

                        context.audit.log(
                            action = "node.client.delete",
                            success = true,
                            actorUserId = null,
                            actorDeviceId = null,
                            targetType = "node",
                            targetId = node.id,
                            detailsJson = "{\"email\":\"${email.replace("\"", "\\\"")}\"}",
                            call = call
                        )

                        call.respond(
                            NodeClientsResponse(
                                nodeId = node.id.toString(),
                                clients = remainingClients.map(::toNodeClientResponse)
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

private fun toAdminNodeResponse(node: NodeEntity): AdminNodeResponse {
    return AdminNodeResponse(
        id = node.id.toString(),
        name = node.name,
        region = node.region,
        countryCode = node.countryCode,
        hostname = node.hostname,
        publicAddress = node.publicAddress,
        publicPort = node.publicPort,
        apiHost = node.apiHost,
        apiPort = node.apiPort,
        inboundTag = node.inboundTag,
        realityServerName = node.realityServerName,
        realityPublicKey = node.realityPublicKey,
        realityShortId = node.realityShortId,
        realityFingerprint = node.realityFingerprint,
        realityAlpn = node.realityAlpn,
        status = node.status,
        health = node.health,
        weight = node.weight,
        load = node.load,
        maxClients = node.maxClients
    )
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
        telegramId = client.telegramId,
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

private val adminPanelHtml = """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>SecureVPN Admin</title>
  <style>
    :root {
      color-scheme: light;
      --bg: #f7f8fa;
      --panel: #ffffff;
      --text: #1c2430;
      --muted: #687487;
      --line: #dbe0e8;
      --accent: #1463ff;
      --ok: #0a7a43;
      --bad: #b42318;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      background: var(--bg);
      color: var(--text);
      font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      font-size: 14px;
    }
    header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      padding: 18px 24px;
      background: var(--panel);
      border-bottom: 1px solid var(--line);
    }
    h1, h2 { margin: 0; letter-spacing: 0; }
    h1 { font-size: 20px; }
    h2 { font-size: 16px; }
    main { width: min(1180px, 100%); margin: 0 auto; padding: 24px; }
    section {
      background: var(--panel);
      border: 1px solid var(--line);
      border-radius: 8px;
      padding: 18px;
      margin-bottom: 18px;
    }
    .toolbar, .grid, .row {
      display: grid;
      gap: 12px;
    }
    .toolbar {
      grid-template-columns: minmax(180px, 1fr) minmax(180px, 1fr) auto auto;
      align-items: end;
    }
    .grid { grid-template-columns: repeat(4, minmax(0, 1fr)); margin-top: 14px; }
    .row { grid-template-columns: repeat(3, minmax(0, 1fr)); margin-top: 14px; }
    label { display: grid; gap: 6px; color: var(--muted); font-size: 12px; font-weight: 600; }
    input, select, button {
      height: 38px;
      border-radius: 6px;
      border: 1px solid var(--line);
      font: inherit;
    }
    input, select { width: 100%; padding: 0 10px; background: #fff; color: var(--text); }
    button {
      padding: 0 14px;
      background: var(--accent);
      color: #fff;
      border-color: var(--accent);
      font-weight: 700;
      cursor: pointer;
      white-space: nowrap;
    }
    button.secondary { background: #fff; color: var(--text); border-color: var(--line); }
    button:disabled { opacity: .55; cursor: not-allowed; }
    table { width: 100%; border-collapse: collapse; margin-top: 12px; }
    th, td { padding: 10px 8px; border-bottom: 1px solid var(--line); text-align: left; vertical-align: middle; }
    th { color: var(--muted); font-size: 12px; text-transform: uppercase; }
    td.actions { display: flex; gap: 8px; flex-wrap: wrap; }
    .status { font-weight: 700; }
    .ok { color: var(--ok); }
    .bad { color: var(--bad); }
    .muted { color: var(--muted); }
    .message { min-height: 20px; margin-top: 10px; color: var(--muted); }
    .hidden { display: none; }
    @media (max-width: 820px) {
      header { align-items: flex-start; flex-direction: column; }
      main { padding: 14px; }
      .toolbar, .grid, .row { grid-template-columns: 1fr; }
      table, thead, tbody, tr, th, td { display: block; }
      thead { display: none; }
      tr { border-bottom: 1px solid var(--line); padding: 8px 0; }
      td { border-bottom: 0; padding: 6px 0; }
      td::before { content: attr(data-label); display: block; color: var(--muted); font-size: 11px; font-weight: 700; text-transform: uppercase; }
    }
  </style>
</head>
<body>
  <header>
    <div>
      <h1>SecureVPN Admin</h1>
      <div class="muted" id="sessionState">Not signed in</div>
    </div>
    <button class="secondary" id="logoutBtn">Log out</button>
  </header>
  <main>
    <section id="loginSection">
      <h2>Admin login</h2>
      <div class="toolbar">
        <label>Email<input id="email" type="email" autocomplete="username" value="admin@securevpn.local"></label>
        <label>Password<input id="password" type="password" autocomplete="current-password"></label>
        <button id="loginBtn">Log in</button>
        <button class="secondary" id="loadBtn">Refresh</button>
      </div>
      <div class="message" id="loginMessage"></div>
    </section>

    <section>
      <h2>Nodes</h2>
      <table>
        <thead>
          <tr><th>Name</th><th>Region</th><th>Status</th><th>Health</th><th>Load</th><th>Weight</th><th>Max clients</th><th>Actions</th></tr>
        </thead>
        <tbody id="nodesBody"></tbody>
      </table>
      <div class="message" id="nodesMessage"></div>
    </section>

    <section id="editNodeSection" class="hidden">
      <h2>Edit node</h2>
      <div class="grid">
        <label>Name<input id="editNodeName"></label>
        <label>Region<input id="editNodeRegion" placeholder="ru"></label>
        <label>Country code<input id="editNodeCountry" placeholder="RU" maxlength="2"></label>
        <label>Hostname<input id="editNodeHostname"></label>
        <label>Public address<input id="editNodePublicAddress"></label>
        <label>Public port<input id="editNodePublicPort" type="number"></label>
        <label>API host<input id="editNodeApiHost"></label>
        <label>API port<input id="editNodeApiPort" type="number"></label>
        <label>Inbound tag<input id="editNodeInboundTag"></label>
        <label>Reality SNI<input id="editNodeRealityServerName"></label>
        <label>Reality public key<input id="editNodeRealityPublicKey"></label>
        <label>Reality short id<input id="editNodeRealityShortId"></label>
        <label>Fingerprint<input id="editNodeRealityFingerprint"></label>
        <label>ALPN<input id="editNodeRealityAlpn"></label>
        <label>Status<select id="editNodeStatus"><option>ACTIVE</option><option>DRAINING</option><option>DISABLED</option></select></label>
        <label>Weight<input id="editNodeWeight" type="number"></label>
        <label>Max clients<input id="editNodeMaxClients" type="number"></label>
      </div>
      <div class="row">
        <button id="saveEditedNodeBtn">Save changes</button>
        <button class="secondary" id="cancelEditNodeBtn">Cancel</button>
      </div>
      <div class="message" id="editNodeMessage"></div>
    </section>

    <section>
      <h2>Create node</h2>
      <div class="grid">
        <label>Name<input id="nodeName"></label>
        <label>Region<input id="nodeRegion" placeholder="ru"></label>
        <label>Country code<input id="nodeCountry" placeholder="RU" maxlength="2"></label>
        <label>Hostname<input id="nodeHostname"></label>
        <label>Public address<input id="nodePublicAddress"></label>
        <label>Public port<input id="nodePublicPort" type="number" value="443"></label>
        <label>API host<input id="nodeApiHost"></label>
        <label>API port<input id="nodeApiPort" type="number" value="10085"></label>
        <label>Inbound tag<input id="nodeInboundTag"></label>
        <label>Reality SNI<input id="nodeRealityServerName" value="www.microsoft.com"></label>
        <label>Reality public key<input id="nodeRealityPublicKey"></label>
        <label>Reality short id<input id="nodeRealityShortId"></label>
        <label>Fingerprint<input id="nodeRealityFingerprint" value="chrome"></label>
        <label>ALPN<input id="nodeRealityAlpn" value="h2,http/1.1"></label>
        <label>Weight<input id="nodeWeight" type="number" value="100"></label>
        <label>Max clients<input id="nodeMaxClients" type="number" value="10000"></label>
      </div>
      <div class="row">
        <button id="createNodeBtn">Create node</button>
      </div>
      <div class="message" id="createMessage"></div>
    </section>

    <section>
      <h2>Grant quota</h2>
      <div class="row">
        <label>User email<input id="quotaEmail" type="email"></label>
        <label>GB<input id="quotaGb" type="number" min="1" value="10"></label>
        <label>External ref<input id="quotaRef"></label>
      </div>
      <div class="row">
        <button id="grantQuotaBtn">Grant quota</button>
      </div>
      <div class="message" id="quotaMessage"></div>
    </section>

    <section id="clientsSection" class="hidden">
      <h2 id="clientsTitle">Node clients</h2>
      <table>
        <thead>
          <tr><th>Email</th><th>Enabled</th><th>Total GB</th><th>Used GB</th><th>Actions</th></tr>
        </thead>
        <tbody id="clientsBody"></tbody>
      </table>
      <div class="message" id="clientsMessage"></div>
    </section>
  </main>
  <script>
    const state = { token: localStorage.getItem("adminToken") || "", nodes: [], selectedNode: null, selectedNodeName: "", editingNodeId: null };
    const el = (id) => document.getElementById(id);
    const gb = (bytes) => (Number(bytes || 0) / 1073741824).toFixed(2);
    const splitAlpn = (value) => value.split(",").map((item) => item.trim()).filter(Boolean);
    const esc = (value) => String(value ?? "").replace(/[&<>"']/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;" }[ch]));
    const setMessage = (id, text, ok) => {
      const node = el(id);
      node.textContent = text || "";
      node.className = "message " + (ok === true ? "ok" : ok === false ? "bad" : "");
    };
    const headers = () => ({ "Content-Type": "application/json", "Authorization": "Bearer " + state.token });
    async function request(path, options) {
      const response = await fetch(path, options || {});
      const text = await response.text();
      let data = null;
      if (text) {
        try { data = JSON.parse(text); } catch (_) { data = text; }
      }
      if (!response.ok) {
        const message = data && data.message ? data.message : response.status + " " + response.statusText;
        throw new Error(message);
      }
      return data;
    }
    function updateSession() {
      el("sessionState").textContent = state.token ? "Signed in" : "Not signed in";
      el("logoutBtn").disabled = !state.token;
    }
    async function login() {
      setMessage("loginMessage", "Signing in...");
      const data = await request("/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: el("email").value, password: el("password").value })
      });
      state.token = data.accessToken;
      localStorage.setItem("adminToken", state.token);
      updateSession();
      setMessage("loginMessage", "Signed in", true);
      await loadNodes();
    }
    async function loadNodes() {
      if (!state.token) {
        setMessage("nodesMessage", "Log in first", false);
        return;
      }
      setMessage("nodesMessage", "Loading...");
      state.nodes = await request("/admin/nodes", { headers: headers() });
      renderNodes();
      setMessage("nodesMessage", state.nodes.length ? "" : "No nodes yet", true);
    }
    function renderNodes() {
      el("nodesBody").innerHTML = "";
      state.nodes.forEach((node) => {
        const tr = document.createElement("tr");
        tr.innerHTML =
          "<td data-label='Name'>" + esc(node.name) + "</td>" +
          "<td data-label='Region'>" + esc(node.region) + "</td>" +
          "<td data-label='Status'><select data-field='status'><option>ACTIVE</option><option>DRAINING</option><option>DISABLED</option></select></td>" +
          "<td data-label='Health'><span class='status " + (node.health === "HEALTHY" ? "ok" : "bad") + "'>" + esc(node.health) + "</span></td>" +
          "<td data-label='Load'>" + node.load + "</td>" +
          "<td data-label='Weight'><input data-field='weight' type='number' value='" + node.weight + "'></td>" +
          "<td data-label='Max clients'><input data-field='maxClients' type='number' value='" + node.maxClients + "'></td>" +
          "<td data-label='Actions' class='actions'></td>";
        tr.querySelector("[data-field=status]").value = node.status;
        const actions = tr.querySelector(".actions");
        const save = document.createElement("button");
        save.textContent = "Save";
        save.onclick = () => updateNode(node.id, tr);
        const edit = document.createElement("button");
        edit.textContent = "Edit";
        edit.className = "secondary";
        edit.onclick = () => editNode(node.id);
        const clients = document.createElement("button");
        clients.textContent = "Clients";
        clients.className = "secondary";
        clients.onclick = () => syncClients(node.id, node.name);
        actions.append(save, edit, clients);
        el("nodesBody").appendChild(tr);
      });
    }
    async function updateNode(id, row) {
      await request("/admin/nodes/" + id, {
        method: "PATCH",
        headers: headers(),
        body: JSON.stringify({
          status: row.querySelector("[data-field=status]").value,
          weight: Number(row.querySelector("[data-field=weight]").value),
          maxClients: Number(row.querySelector("[data-field=maxClients]").value)
        })
      });
      setMessage("nodesMessage", "Node updated", true);
      await loadNodes();
    }
    function nodePayload(prefix) {
      return {
        name: el(prefix + "Name").value,
        region: el(prefix + "Region").value,
        countryCode: el(prefix + "Country").value,
        hostname: el(prefix + "Hostname").value,
        publicAddress: el(prefix + "PublicAddress").value,
        publicPort: Number(el(prefix + "PublicPort").value),
        apiHost: el(prefix + "ApiHost").value,
        apiPort: Number(el(prefix + "ApiPort").value),
        inboundTag: el(prefix + "InboundTag").value,
        realityServerName: el(prefix + "RealityServerName").value,
        realityPublicKey: el(prefix + "RealityPublicKey").value,
        realityShortId: el(prefix + "RealityShortId").value,
        realityFingerprint: el(prefix + "RealityFingerprint").value,
        realityAlpn: splitAlpn(el(prefix + "RealityAlpn").value),
        weight: Number(el(prefix + "Weight").value),
        maxClients: Number(el(prefix + "MaxClients").value)
      };
    }
    function editNode(id) {
      const node = state.nodes.find((item) => item.id === id);
      if (!node) return;
      state.editingNodeId = id;
      el("editNodeName").value = node.name;
      el("editNodeRegion").value = node.region;
      el("editNodeCountry").value = node.countryCode;
      el("editNodeHostname").value = node.hostname;
      el("editNodePublicAddress").value = node.publicAddress;
      el("editNodePublicPort").value = node.publicPort;
      el("editNodeApiHost").value = node.apiHost;
      el("editNodeApiPort").value = node.apiPort;
      el("editNodeInboundTag").value = node.inboundTag;
      el("editNodeRealityServerName").value = node.realityServerName;
      el("editNodeRealityPublicKey").value = node.realityPublicKey;
      el("editNodeRealityShortId").value = node.realityShortId;
      el("editNodeRealityFingerprint").value = node.realityFingerprint;
      el("editNodeRealityAlpn").value = (node.realityAlpn || []).join(",");
      el("editNodeStatus").value = node.status;
      el("editNodeWeight").value = node.weight;
      el("editNodeMaxClients").value = node.maxClients;
      el("editNodeSection").classList.remove("hidden");
      setMessage("editNodeMessage", "");
      el("editNodeSection").scrollIntoView({ behavior: "smooth", block: "start" });
    }
    function cancelEditNode() {
      state.editingNodeId = null;
      el("editNodeSection").classList.add("hidden");
      setMessage("editNodeMessage", "");
    }
    async function saveEditedNode() {
      if (!state.editingNodeId) return;
      const payload = nodePayload("editNode");
      payload.status = el("editNodeStatus").value;
      await request("/admin/nodes/" + state.editingNodeId, {
        method: "PATCH",
        headers: headers(),
        body: JSON.stringify(payload)
      });
      setMessage("editNodeMessage", "Node updated", true);
      await loadNodes();
    }
    async function createNode() {
      const payload = nodePayload("node");
      await request("/admin/nodes", { method: "POST", headers: headers(), body: JSON.stringify(payload) });
      setMessage("createMessage", "Node created", true);
      await loadNodes();
    }
    async function grantQuota() {
      const payload = { email: el("quotaEmail").value, gb: Number(el("quotaGb").value), source: "admin_panel" };
      if (el("quotaRef").value) payload.externalRef = el("quotaRef").value;
      const data = await request("/admin/quota/grant", { method: "POST", headers: headers(), body: JSON.stringify(payload) });
      setMessage("quotaMessage", "Granted. Remaining: " + data.remainingGb.toFixed(2) + " GB", true);
    }
    async function syncClients(nodeId, nodeName) {
      state.selectedNode = nodeId;
      state.selectedNodeName = nodeName;
      el("clientsSection").classList.remove("hidden");
      el("clientsTitle").textContent = "Node clients: " + nodeName;
      setMessage("clientsMessage", "Syncing...");
      const data = await request("/admin/nodes/" + nodeId + "/clients/sync", { method: "POST", headers: headers() });
      renderClients(data.clients || []);
      setMessage("clientsMessage", "Synced " + data.synced + " clients", true);
    }
    function renderClients(clients) {
      el("clientsBody").innerHTML = "";
      clients.forEach((client) => {
        const tr = document.createElement("tr");
        const used = Number(client.upBytes || 0) + Number(client.downBytes || 0);
        tr.innerHTML =
          "<td data-label='Email'>" + esc(client.email) + "</td>" +
          "<td data-label='Enabled'>" + client.enabled + "</td>" +
          "<td data-label='Total GB'><input data-field='totalGb' type='number' min='0' value='" + gb(client.totalBytes) + "'></td>" +
          "<td data-label='Used GB'>" + gb(used) + "</td>" +
          "<td data-label='Actions' class='actions'></td>";
        const save = document.createElement("button");
        save.textContent = "Set limit";
        save.onclick = () => updateClientLimit(client.email, tr);
        const remove = document.createElement("button");
        remove.textContent = "Delete";
        remove.className = "secondary";
        remove.onclick = () => deleteClient(client.email);
        tr.querySelector(".actions").append(save, remove);
        el("clientsBody").appendChild(tr);
      });
    }
    async function updateClientLimit(email, row) {
      await request("/admin/nodes/" + state.selectedNode + "/clients/" + encodeURIComponent(email), {
        method: "PATCH",
        headers: headers(),
        body: JSON.stringify({ totalGb: Number(row.querySelector("[data-field=totalGb]").value) })
      });
      setMessage("clientsMessage", "Client limit updated", true);
    }
    async function deleteClient(email) {
      if (!state.selectedNode) return;
      if (!confirm("Delete client " + email + "?")) return;
      setMessage("clientsMessage", "Deleting...");
      const data = await request("/admin/nodes/" + state.selectedNode + "/clients/" + encodeURIComponent(email), {
        method: "DELETE",
        headers: headers()
      });
      renderClients(data.clients || []);
      setMessage("clientsMessage", "Client deleted", true);
    }
    el("loginBtn").onclick = () => login().catch((error) => setMessage("loginMessage", error.message, false));
    el("loadBtn").onclick = () => loadNodes().catch((error) => setMessage("nodesMessage", error.message, false));
    el("logoutBtn").onclick = () => {
      state.token = "";
      localStorage.removeItem("adminToken");
      updateSession();
    };
    el("createNodeBtn").onclick = () => createNode().catch((error) => setMessage("createMessage", error.message, false));
    el("saveEditedNodeBtn").onclick = () => saveEditedNode().catch((error) => setMessage("editNodeMessage", error.message, false));
    el("cancelEditNodeBtn").onclick = cancelEditNode;
    el("grantQuotaBtn").onclick = () => grantQuota().catch((error) => setMessage("quotaMessage", error.message, false));
    updateSession();
    if (state.token) loadNodes().catch((error) => setMessage("nodesMessage", error.message, false));
  </script>
</body>
</html>
""".trimIndent()
