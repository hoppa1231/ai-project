package com.example

import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("VPN Server API\n\nEndpoints:\n- GET /vpn/nodes\n- GET /health",
                contentType = ContentType.Text.Plain)
        }

        get("/vpn/nodes") {
            try {
                val servers = transaction {
                    ServersTable.selectAll().map { row ->
                        mapOf(
                            "id" to row[ServersTable.id].toString(),
                            "name" to row[ServersTable.name],
                            "country" to row[ServersTable.country],
                            "city" to row[ServersTable.city],
                            "load" to row[ServersTable.currentLoadPercent],
                            "hostname" to row[ServersTable.hostname],
                            "ip" to row[ServersTable.ipAddress]
                        )
                    }
                }
                call.respond(mapOf("servers" to servers))
            } catch (e: Exception) {
                call.respond(mapOf("error" to e.message))
            }
        }

        get("/health") {
            try {
                val dbStatus = checkDatabaseHealth()
                call.respond(mapOf(
                    "status" to "OK",
                    "database" to if (dbStatus) "connected" else "error",
                    "timestamp" to Instant.now().toString()
                ))
            } catch (e: Exception) {
                call.respond(mapOf(
                    "status" to "ERROR",
                    "database" to "disconnected",
                    "error" to e.message
                ))
            }
        }

        get("/policy/current") { call.respond(mapOf("message" to "Not implemented")) }
        post("/auth/register") { call.respond(mapOf("message" to "Not implemented")) }
        post("/auth/login") { call.respond(mapOf("message" to "Not implemented")) }
        post("/devices/bind") { call.respond(mapOf("message" to "Not implemented")) }
        post("/vpn/config") { call.respond(mapOf("message" to "Not implemented")) }
        post("/telemetry") { call.respond(mapOf("message" to "Not implemented")) }
    }
}