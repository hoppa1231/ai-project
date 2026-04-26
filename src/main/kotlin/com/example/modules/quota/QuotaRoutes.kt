package com.example.modules.quota

import com.example.common.ApiException
import com.example.config.AppContext
import com.example.security.requireUserId
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
data class QuotaResponse(
    val accountType: String,
    val cycleStart: String,
    val cycleEnd: String,
    val freeBytes: Long,
    val purchasedBytes: Long,
    val usedBytes: Long,
    val remainingBytes: Long,
    val freeGb: Double,
    val purchasedGb: Double,
    val usedGb: Double,
    val remainingGb: Double
)

fun Application.configureQuotaRoutes(context: AppContext) {
    routing {
        authenticate("auth-jwt") {
            get("/quota/current") {
                val principal = call.principal<JWTPrincipal>()
                    ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                val userId = principal.requireUserId()
                val user = context.users.findById(userId)
                    ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "User not found")

                val quota = context.quota.current(userId, context.config.freeGbPerMonth)
                call.respond(
                    QuotaResponse(
                        accountType = user.accountType,
                        cycleStart = quota.cycleStart.toString(),
                        cycleEnd = quota.cycleEnd.toString(),
                        freeBytes = quota.freeBytes,
                        purchasedBytes = quota.purchasedBytes,
                        usedBytes = quota.usedBytes,
                        remainingBytes = quota.remainingBytes,
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

private fun bytesToGb(bytes: Long): Double = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
