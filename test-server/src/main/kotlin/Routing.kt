package com.example

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.request.receive
import io.ktor.server.routing.*
import io.ktor.http.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlinx.serialization.Serializable
import org.mindrot.jbcrypt.BCrypt

@Serializable
data class UserResponse(
    val id: Int,
    val login: String,
    val password: String,
    val status: String = "OFFLINE"
)

@Serializable
data class UserRequest(
    val login: String,
    val password: String,
    val status: String
)

@Serializable
data class AuthRequest(
    val login: String,
    val password: String
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String
)

@Serializable
data class RefreshRequest(
    val refreshToken: String
)

@Serializable
data class VpnNodeResponse(
    val id: Int,
    val region: String,
    val address: String,
    val status: String,
    val online: Int
)

@Serializable
data class VpnNodeRequest(
    val region: String,
    val address: String,
    val status: String,
    val online: Int
)

@Serializable
data class NodeClientResponse(
    val id: Int,
    val serverId: Int?,
    val client: String,
    val trafficId: Int?
)

@Serializable
data class NodeClientRequest(
    val serverId: Int?,
    val client: String,
    val trafficId: Int?
)

@Serializable
data class PolicyResponse(
    val id: Int,
    val filename: String,
    val userId: Int?
)

@Serializable
data class PolicyRequest(
    val filename: String,
    val userId: Int?
)

@Serializable
data class IssuedConfigResponse(
    val id: Int,
    val version: Int,
    val name: String,
    val serverId: Int?
)

@Serializable
data class IssuedConfigRequest(
    val version: Int,
    val name: String,
    val serverId: Int?
)

@Serializable
data class TrafficResponse(
    val id: Int,
    val userId: Int?,
    val limit: Float?,
    val current: Double
)

@Serializable
data class TrafficRequest(
    val userId: Int?,
    val limit: Float?,
    val current: Double
)

private fun hashPassword(password: String): String = BCrypt.hashpw(password, BCrypt.gensalt())

fun Application.configureRouting() {
    val cfg = jwtConfig()

    routing {
        // Auth (публичные эндпоинты)
        post("/auth/register") {
            val body = call.receive<AuthRequest>()

            val exists = transaction { Users_table.find { Users_tables.login eq body.login }.firstOrNull() }
            if (exists != null) return@post call.respond(HttpStatusCode.Conflict, "Login already taken")

            val newUser = transaction {
                Users_table.new {
                    login    = body.login
                    password = hashPassword(body.password)
                    status   = StatusUser.of("OFFLINE")
                }
            }

            val accessToken  = generateAccessToken(cfg, newUser.id.value, newUser.login)
            val refreshToken = generateRefreshToken(cfg, newUser.id.value)
            call.respond(HttpStatusCode.Created, AuthResponse(accessToken, refreshToken))
        }

        post("/auth/login") {
            val body = call.receive<AuthRequest>()

            val user = transaction { Users_table.find { Users_tables.login eq body.login }.firstOrNull() }
                ?: return@post call.respond(HttpStatusCode.Unauthorized, "Invalid credentials")

            if (!BCrypt.checkpw(body.password, user.password))
                return@post call.respond(HttpStatusCode.Unauthorized, "Invalid credentials")

            val accessToken  = generateAccessToken(cfg, user.id.value, user.login)
            val refreshToken = generateRefreshToken(cfg, user.id.value)
            call.respond(AuthResponse(accessToken, refreshToken))
        }

        post("/auth/refresh") {
            val body = call.receive<RefreshRequest>()

            val decoded = runCatching {
                JWT.require(Algorithm.HMAC256(cfg.secret))
                    .withIssuer(cfg.issuer)
                    .withAudience(cfg.audience)
                    .withClaim("type", "refresh")
                    .build()
                    .verify(body.refreshToken)
            }.getOrNull() ?: return@post call.respond(HttpStatusCode.Unauthorized, "Invalid refresh token")

            val userId = decoded.getClaim("userId").asInt()
            val user = transaction { Users_table.findById(userId) }
                ?: return@post call.respond(HttpStatusCode.Unauthorized, "User not found")

            val accessToken  = generateAccessToken(cfg, user.id.value, user.login)
            val refreshToken = generateRefreshToken(cfg, user.id.value)
            call.respond(AuthResponse(accessToken, refreshToken))
        }

        // ------ //
        // CRUD (защищено JWT)
        authenticate("auth-jwt") {

        get("/vpn/nodes") {
            call.respondText("доступные сервера: МАЙНКРАФТ")
        }
        get("/policy/current") {
            call.respondText("политика: ДЛЯ ОДАРЁННЫХ")
        }
        post("/devices/bind"){
            call.respondText("мобилки компутеры аппараты жизнеобеспечения")
        }
        post("/vpn/config"){
            call.respondText("конфигурация: ДЛЯ ДЕБИЛИЗАЦИИ")
        }
        post("/telemetry"){
            call.respondText("телеметрия: ИБО ТОК ТЕЛЕК ОСТАЛСЯ")
        }

        // Пользователи
        get("/users") {
            val users = transaction {
                Users_table
                    .all()
                    .orderBy(Users_tables.id to SortOrder.ASC)
                    .map {
                        UserResponse(
                            id = it.id.value,
                            login = it.login,
                            password = it.password,
                            status = it.status.value
                        )
                    }
            }

            call.respond(users)
        }

        get("/users/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid id")

            val user = transaction {
                Users_table.findById(id)
            } ?: return@get call.respond(HttpStatusCode.NotFound, "User not found")

            call.respond(UserResponse(
                id = user.id.value,
                login = user.login,
                password = user.password,
                status = user.status.value
            ))
        }

        post("/users") {
            val body = call.receive<UserRequest>()

            val status = StatusUser.safeOf(body.status)
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid status: ${body.status}")

            val newUser = transaction {
                Users_table.new {
                    login    = body.login
                    password = hashPassword(body.password)
                    this.status = status
                }
            }

            call.respond(HttpStatusCode.Created, mapOf("id" to newUser.id.value))
        }

        put("/users/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid id")
            
            val body = call.receive<UserRequest>()

            val status = StatusUser.safeOf(body.status)
            ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid status: ${body.status}")

            val updated = transaction {
                val user = Users_table.findById(id) ?: return@transaction null
                user.login = body.login
                user.password = hashPassword(body.password)
                user.status = status
                user
            } ?: return@put call.respond(HttpStatusCode.NotFound, "User not found")

            call.respond(HttpStatusCode.OK, UserResponse(
                id = updated.id.value,
                login = updated.login,
                password = updated.password,
                status = updated.status.value
            ))
        }

        delete("/users/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid id")

            val deleted = transaction {
                val user = Users_table.findById(id) ?: return@transaction false
                user.delete()
                true
            }

            if (deleted) call.respondText("User deleted", ContentType.Text.Plain, HttpStatusCode.OK)
            else call.respond(HttpStatusCode.NotFound, "User not found")
        }

        // VPN-ноды
        get("/vpn-nodes") {
            val nodes = transaction {
                VPN_nodes_table
                    .all()
                    .orderBy(VPN_nodes_tables.id to SortOrder.ASC)
                    .map {
                    VpnNodeResponse(
                        id = it.id.value,
                        region = it.region,
                        address = it.address,
                        status = it.status.value,
                        online = it.online
                    )
                }
            }

            call.respond(nodes)
        }

        get("/vpn-nodes/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid id")

            val node = transaction {
                VPN_nodes_table.findById(id)
            } ?: return@get call.respond(HttpStatusCode.NotFound, "VPN node not found")

            call.respond(
                VpnNodeResponse(
                    id = node.id.value,
                    region = node.region,
                    address = node.address,
                    status = node.status.value,
                    online = node.online
                )
            )
        }

        post("/vpn-nodes") {
            val body = call.receive<VpnNodeRequest>()

            val status = StatusNode.safeOf(body.status)
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid status: ${body.status}")

            val newNode = transaction {
                VPN_nodes_table.new {
                    region = body.region
                    address = body.address
                    this.status = status
                    online = body.online
                }
            }

            call.respond(HttpStatusCode.Created, mapOf("id" to newNode.id.value))
        }

        put("/vpn-nodes/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid id")

            val body = call.receive<VpnNodeRequest>()

            val status = StatusNode.safeOf(body.status)
                ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid status: ${body.status}")

            val updated = transaction {
                val node = VPN_nodes_table.findById(id) ?: return@transaction null
                node.region = body.region
                node.address = body.address
                node.status = status
                node.online = body.online
                node
            } ?: return@put call.respond(HttpStatusCode.NotFound, "VPN node not found")

            call.respond(
                HttpStatusCode.OK,
                VpnNodeResponse(
                    id = updated.id.value,
                    region = updated.region,
                    address = updated.address,
                    status = updated.status.value,
                    online = updated.online
                )
            )
        }

        delete("/vpn-nodes/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid id")

            val deleted = transaction {
                val node = VPN_nodes_table.findById(id) ?: return@transaction false
                node.delete()
                true
            }

            if (deleted) call.respondText("VPN node deleted", ContentType.Text.Plain, HttpStatusCode.OK)
            else call.respond(HttpStatusCode.NotFound, "VPN node not found")
        }

        // Клиенты нод
        get("/node-clients") {
            val clients = transaction {
                Node_clients_table
                    .all()
                    .orderBy(Node_clients_tables.id to SortOrder.ASC)
                    .map {
                        NodeClientResponse(
                            id = it.id.value,
                            serverId = it.server?.value,
                            client = it.client,
                            trafficId = it.traffic?.value
                        )
                    }
            }

            call.respond(clients)
        }

        get("/node-clients/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid id")

            val clientNode = transaction {
                Node_clients_table.findById(id)
            } ?: return@get call.respond(HttpStatusCode.NotFound, "Node client not found")

            call.respond(
                NodeClientResponse(
                    id = clientNode.id.value,
                    serverId = clientNode.server?.value,
                    client = clientNode.client,
                    trafficId = clientNode.traffic?.value
                )
            )
        }

        post("/node-clients") {
            val body = call.receive<NodeClientRequest>()

            if (body.serverId != null) {
                val serverExists = transaction { VPN_nodes_table.findById(body.serverId) != null }
                if (!serverExists) return@post call.respond(HttpStatusCode.BadRequest, "Server not found: ${body.serverId}")
            }

            if (body.trafficId != null) {
                val trafficExists = transaction { Traffic_use_table.findById(body.trafficId) != null }
                if (!trafficExists) return@post call.respond(HttpStatusCode.BadRequest, "Traffic not found: ${body.trafficId}")
            }

            val newClient = transaction {
                Node_clients_table.new {
                    server = body.serverId?.let { VPN_nodes_table.findById(it)!!.id }
                    client = body.client
                    traffic = body.trafficId?.let { Traffic_use_table.findById(it)!!.id }
                }
            }

            call.respond(HttpStatusCode.Created, mapOf("id" to newClient.id.value))
        }

        put("/node-clients/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid id")

            val body = call.receive<NodeClientRequest>()

            if (body.serverId != null) {
                val serverExists = transaction { VPN_nodes_table.findById(body.serverId) != null }
                if (!serverExists) return@put call.respond(HttpStatusCode.BadRequest, "Server not found: ${body.serverId}")
            }

            if (body.trafficId != null) {
                val trafficExists = transaction { Traffic_use_table.findById(body.trafficId) != null }
                if (!trafficExists) return@put call.respond(HttpStatusCode.BadRequest, "Traffic not found: ${body.trafficId}")
            }

            val updated = transaction {
                val clientNode = Node_clients_table.findById(id) ?: return@transaction null
                clientNode.server = body.serverId?.let { VPN_nodes_table.findById(it)!!.id }
                clientNode.client = body.client
                clientNode.traffic = body.trafficId?.let { Traffic_use_table.findById(it)!!.id }
                clientNode
            } ?: return@put call.respond(HttpStatusCode.NotFound, "Node client not found")

            call.respond(
                HttpStatusCode.OK,
                NodeClientResponse(
                    id = updated.id.value,
                    serverId = updated.server?.value,
                    client = updated.client,
                    trafficId = updated.traffic?.value
                )
            )
        }

        delete("/node-clients/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid id")

            val deleted = transaction {
                val clientNode = Node_clients_table.findById(id) ?: return@transaction false
                clientNode.delete()
                true
            }

            if (deleted) call.respondText("Node client deleted", ContentType.Text.Plain, HttpStatusCode.OK)
            else call.respond(HttpStatusCode.NotFound, "Node client not found")
        }

        // Политики
        get("/policies") {
            val policies = transaction {
                Policies_table
                    .all()
                    .orderBy(Policies_tables.id to SortOrder.ASC)
                    .map {
                        PolicyResponse(
                            id = it.id.value,
                            filename = it.filename,
                            userId = it.user?.value
                        )
                    }
            }

            call.respond(policies)
        }

        get("/policies/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid id")

            val policy = transaction {
                Policies_table.findById(id)
            } ?: return@get call.respond(HttpStatusCode.NotFound, "Policy not found")

            call.respond(
                PolicyResponse(
                    id = policy.id.value,
                    filename = policy.filename,
                    userId = policy.user?.value
                )
            )
        }

        post("/policies") {
            val body = call.receive<PolicyRequest>()

            if (body.userId != null) {
                val userExists = transaction { Users_table.findById(body.userId) != null }
                if (!userExists) return@post call.respond(HttpStatusCode.BadRequest, "User not found: ${body.userId}")
            }

            val newPolicy = transaction {
                Policies_table.new {
                    filename = body.filename
                    user = body.userId?.let { Users_table.findById(it)!!.id }
                }
            }

            call.respond(HttpStatusCode.Created, mapOf("id" to newPolicy.id.value))
        }

        put("/policies/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid id")

            val body = call.receive<PolicyRequest>()

            if (body.userId != null) {
                val userExists = transaction { Users_table.findById(body.userId) != null }
                if (!userExists) return@put call.respond(HttpStatusCode.BadRequest, "User not found: ${body.userId}")
            }

            val updated = transaction {
                val policy = Policies_table.findById(id) ?: return@transaction null
                policy.filename = body.filename
                policy.user = body.userId?.let { Users_table.findById(it)!!.id }
                policy
            } ?: return@put call.respond(HttpStatusCode.NotFound, "Policy not found")

            call.respond(
                HttpStatusCode.OK,
                PolicyResponse(
                    id = updated.id.value,
                    filename = updated.filename,
                    userId = updated.user?.value
                )
            )
        }

        delete("/policies/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid id")

            val deleted = transaction {
                val policy = Policies_table.findById(id) ?: return@transaction false
                policy.delete()
                true
            }

            if (deleted) call.respondText("Policy deleted", ContentType.Text.Plain, HttpStatusCode.OK)
            else call.respond(HttpStatusCode.NotFound, "Policy not found")
        }

        // Выданные конфиги
        get("/issued-configs") {
            val configs = transaction {
                Issued_configs_table
                    .all()
                    .orderBy(Issued_configs_tables.id to SortOrder.ASC)
                    .map {
                        IssuedConfigResponse(
                            id = it.id.value,
                            version = it.version,
                            name = it.name,
                            serverId = it.server?.value
                        )
                    }
            }

            call.respond(configs)
        }

        get("/issued-configs/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid id")

            val config = transaction {
                Issued_configs_table.findById(id)
            } ?: return@get call.respond(HttpStatusCode.NotFound, "Issued config not found")

            call.respond(
                IssuedConfigResponse(
                    id = config.id.value,
                    version = config.version,
                    name = config.name,
                    serverId = config.server?.value
                )
            )
        }

        post("/issued-configs") {
            val body = call.receive<IssuedConfigRequest>()

            if (body.serverId != null) {
                val serverExists = transaction { VPN_nodes_table.findById(body.serverId) != null }
                if (!serverExists) return@post call.respond(HttpStatusCode.BadRequest, "Server not found: ${body.serverId}")
            }

            val newConfig = transaction {
                Issued_configs_table.new {
                    version = body.version
                    name = body.name
                    server = body.serverId?.let { VPN_nodes_table.findById(it)!!.id }
                }
            }

            call.respond(HttpStatusCode.Created, mapOf("id" to newConfig.id.value))
        }

        put("/issued-configs/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid id")

            val body = call.receive<IssuedConfigRequest>()

            if (body.serverId != null) {
                val serverExists = transaction { VPN_nodes_table.findById(body.serverId) != null }
                if (!serverExists) return@put call.respond(HttpStatusCode.BadRequest, "Server not found: ${body.serverId}")
            }

            val updated = transaction {
                val config = Issued_configs_table.findById(id) ?: return@transaction null
                config.version = body.version
                config.name = body.name
                config.server = body.serverId?.let { VPN_nodes_table.findById(it)!!.id }
                config
            } ?: return@put call.respond(HttpStatusCode.NotFound, "Issued config not found")

            call.respond(
                HttpStatusCode.OK,
                IssuedConfigResponse(
                    id = updated.id.value,
                    version = updated.version,
                    name = updated.name,
                    serverId = updated.server?.value
                )
            )
        }

        delete("/issued-configs/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid id")

            val deleted = transaction {
                val config = Issued_configs_table.findById(id) ?: return@transaction false
                config.delete()
                true
            }

            if (deleted) call.respondText("Issued config deleted", ContentType.Text.Plain, HttpStatusCode.OK)
            else call.respond(HttpStatusCode.NotFound, "Issued config not found")
        }

        // Трафик
        get("/traffic") {
            val trafficList = transaction {
                Traffic_use_table
                    .all()
                    .orderBy(Traffic_use_tables.id to SortOrder.ASC)
                    .map {
                        TrafficResponse(
                            id = it.id.value,
                            userId = it.user?.value,
                            limit = it.limit,
                            current = it.current
                        )
                    }
            }

            call.respond(trafficList)
        }

        get("/traffic/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid id")

            val traffic = transaction {
                Traffic_use_table.findById(id)
            } ?: return@get call.respond(HttpStatusCode.NotFound, "Traffic not found")

            call.respond(
                TrafficResponse(
                    id = traffic.id.value,
                    userId = traffic.user?.value,
                    limit = traffic.limit,
                    current = traffic.current
                )
            )
        }

        post("/traffic") {
            val body = call.receive<TrafficRequest>()

            if (body.userId != null) {
                val userExists = transaction { Users_table.findById(body.userId) != null }
                if (!userExists) return@post call.respond(HttpStatusCode.BadRequest, "User not found: ${body.userId}")
            }

            val newTraffic = transaction {
                Traffic_use_table.new {
                    user = body.userId?.let { Users_table.findById(it)!!.id }
                    limit = body.limit
                    current = body.current
                }
            }

            call.respond(HttpStatusCode.Created, mapOf("id" to newTraffic.id.value))
        }

        put("/traffic/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid id")

            val body = call.receive<TrafficRequest>()

            if (body.userId != null) {
                val userExists = transaction { Users_table.findById(body.userId) != null }
                if (!userExists) return@put call.respond(HttpStatusCode.BadRequest, "User not found: ${body.userId}")
            }

            val updated = transaction {
                val traffic = Traffic_use_table.findById(id) ?: return@transaction null
                traffic.user = body.userId?.let { Users_table.findById(it)!!.id }
                traffic.limit = body.limit
                traffic.current = body.current
                traffic
            } ?: return@put call.respond(HttpStatusCode.NotFound, "Traffic not found")

            call.respond(
                HttpStatusCode.OK,
                TrafficResponse(
                    id = updated.id.value,
                    userId = updated.user?.value,
                    limit = updated.limit,
                    current = updated.current
                )
            )
        }

        delete("/traffic/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid id")

            val deleted = transaction {
                val traffic = Traffic_use_table.findById(id) ?: return@transaction false
                traffic.delete()
                true
            }

            if (deleted) call.respondText("Traffic deleted", ContentType.Text.Plain, HttpStatusCode.OK)
            else call.respond(HttpStatusCode.NotFound, "Traffic not found")
        }

        } // end authenticate("auth-jwt")
    }
}

//        "====================================================================================\n" +
//        "=====00=======0000000==0000000000==00==00000000========0000000==00=======00=========\n" +
//        "=====00=======00===========00======00==00==============00===00==00=======00=========\n" +
//        "=====00=======0000000======00======00==00000000========0000000==00=======00=========\n" +
//        "=====00=======00===========00================00========00===00==00=======00=========\n" +
//        "=====0000000==0000000======00==========00000000========00===00==0000000==0000000====\n" +
//        "====================================================================================\n" +
//        "==00=======00000000==00===00==0000000========00=======0000000==00000000===000===00==\n" +
//        "==00=======00====00==00===00==00=============00=======00===00=====00======0000==00==\n" +
//        "==00=======00====00==00===00==0000000========00=======0000000=====00======00=00=00==\n" +
//        "==00=======00====00===00=00===00=============00=======00===00=====00======00==0000==\n" +
//        "==0000000==00000000====000====0000000========0000000==00===00==00000000===00===000==\n" +
//        "===================================================================================="