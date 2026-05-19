package com.example.modules.notifications

import com.example.common.ApiException
import com.example.config.AppContext
import com.example.db.AppNotificationEntity
import com.example.security.deviceIdOrNull
import com.example.security.requireRole
import com.example.security.requireUserId
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Serializable
data class AppNotificationResponse(
    val id: String,
    val title: String,
    val body: String,
    val severity: String,
    val startsAt: String,
    val expiresAt: String?,
    val createdAt: String
)

@Serializable
data class AppNotificationsResponse(
    val notifications: List<AppNotificationResponse>
)

@Serializable
data class SendNotificationRequest(
    val title: String,
    val body: String,
    val severity: String = "INFO",
    val email: String? = null,
    val userId: String? = null,
    val deviceId: String? = null,
    val ttlHours: Long? = null
)

fun Application.configureNotificationRoutes(context: AppContext) {
    routing {
        authenticate("auth-jwt") {
            route("/notifications") {
                get {
                    val principal = call.principal<JWTPrincipal>()
                        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                    val userId = principal.requireUserId()
                    val deviceId = principal.deviceIdOrNull()
                    val includeRead = call.request.queryParameters["includeRead"] == "true"

                    call.respond(
                        AppNotificationsResponse(
                            notifications = context.notifications
                                .listActiveFor(userId, deviceId, includeRead)
                                .map(::toResponse)
                        )
                    )
                }

                post("/{id}/read") {
                    val principal = call.principal<JWTPrincipal>()
                        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                    val userId = principal.requireUserId()
                    val deviceId = principal.deviceIdOrNull()
                        ?: throw ApiException(HttpStatusCode.Forbidden, "DEVICE_NOT_BOUND", "Token is not bound to device")
                    val notificationId = parseUuid(call.parameters["id"], "INVALID_NOTIFICATION_ID")
                    context.notifications.markRead(notificationId, userId, deviceId)
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            post("/admin/notifications") {
                val principal = call.principal<JWTPrincipal>()
                    ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                if (principal.requireRole() != "ADMIN") {
                    throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "Admin role required")
                }

                val body = call.receive<SendNotificationRequest>()
                val title = body.title.trim()
                val message = body.body.trim()
                if (title.isBlank() || message.isBlank()) {
                    throw ApiException(HttpStatusCode.BadRequest, "INVALID_NOTIFICATION", "title and body are required")
                }

                val userFromEmail = body.email?.trim()?.takeIf { it.isNotBlank() }?.let { email ->
                    context.users.findByEmail(email)
                        ?: throw ApiException(HttpStatusCode.NotFound, "USER_NOT_FOUND", "User not found")
                }
                val userFromId = body.userId?.let { rawUserId ->
                    val parsed = parseUuid(rawUserId, "INVALID_USER_ID")
                    context.users.findById(parsed)
                        ?: throw ApiException(HttpStatusCode.NotFound, "USER_NOT_FOUND", "User not found")
                }
                if (userFromEmail != null && userFromId != null && userFromEmail.id != userFromId.id) {
                    throw ApiException(HttpStatusCode.BadRequest, "USER_MISMATCH", "email and userId point to different users")
                }
                val userId = userFromEmail?.id ?: userFromId?.id
                val deviceId = body.deviceId?.let { parseUuid(it, "INVALID_DEVICE_ID") }
                if (deviceId != null && userId == null) {
                    throw ApiException(HttpStatusCode.BadRequest, "USER_REQUIRED", "email is required with deviceId")
                }
                if (deviceId != null && context.devices.findByIdForUser(deviceId, userId!!) == null) {
                    throw ApiException(HttpStatusCode.NotFound, "DEVICE_NOT_FOUND", "Device not found")
                }

                val notification = context.notifications.create(
                    title = title,
                    body = message,
                    severity = normalizeSeverity(body.severity),
                    targetUserId = userId,
                    targetDeviceId = deviceId,
                    expiresAt = body.ttlHours?.let { Instant.now().plus(Duration.ofHours(it)) },
                    createdByUserId = principal.requireUserId()
                )

                call.respond(HttpStatusCode.Created, toResponse(notification))
            }
        }
    }
}

private fun parseUuid(raw: String?, code: String): UUID {
    val value = raw ?: throw ApiException(HttpStatusCode.BadRequest, code, "Invalid UUID")
    return runCatching { UUID.fromString(value) }.getOrElse {
        throw ApiException(HttpStatusCode.BadRequest, code, "Invalid UUID")
    }
}

private fun normalizeSeverity(raw: String): String {
    val severity = raw.trim().uppercase()
    if (severity in setOf("INFO", "WARNING", "CRITICAL")) return severity
    throw ApiException(HttpStatusCode.BadRequest, "INVALID_SEVERITY", "severity must be INFO, WARNING or CRITICAL")
}

private fun toResponse(notification: AppNotificationEntity): AppNotificationResponse {
    return AppNotificationResponse(
        id = notification.id.toString(),
        title = notification.title,
        body = notification.body,
        severity = notification.severity,
        startsAt = notification.startsAt.toString(),
        expiresAt = notification.expiresAt?.toString(),
        createdAt = notification.createdAt.toString()
    )
}
