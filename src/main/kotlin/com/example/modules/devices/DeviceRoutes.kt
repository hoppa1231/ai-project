package com.example.modules.devices

import com.example.common.ApiException
import com.example.common.Hashing
import com.example.config.AppContext
import com.example.security.AccessClaims
import com.example.security.requireUserId
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
import java.time.Instant

@Serializable
data class BindDeviceRequest(
    val deviceFingerprint: String,
    val deviceName: String,
    val platform: String,
    val appVersion: String? = null,
    val publicKey: String? = null
)

@Serializable
data class BindDeviceResponse(
    val deviceId: String,
    val status: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long
)

fun Application.configureDeviceRoutes(context: AppContext) {
    routing {
        authenticate("auth-jwt") {
            route("/devices") {
                post("/bind") {
                    val principal = call.principal<JWTPrincipal>()
                        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                    val userId = principal.requireUserId()

                    val body = call.receive<BindDeviceRequest>()
                    if (body.deviceFingerprint.length < 8) {
                        throw ApiException(HttpStatusCode.BadRequest, "INVALID_DEVICE_FINGERPRINT", "Invalid fingerprint")
                    }

                    val fingerprintHash = Hashing.sha256Hex("${context.config.hashPepper}:${body.deviceFingerprint}")
                    val device = context.devices.bindDevice(
                        userId = userId,
                        fingerprintHash = fingerprintHash,
                        deviceName = body.deviceName,
                        platform = body.platform,
                        appVersion = body.appVersion,
                        publicKey = body.publicKey
                    )

                    val role = principal.payload.getClaim("role").asString() ?: "USER"
                    val access = context.jwt.issueAccess(
                        AccessClaims(userId = userId, role = role, deviceId = device.id)
                    )
                    val (refresh, jti) = context.jwt.issueRefresh(
                        AccessClaims(userId = userId, role = role, deviceId = device.id)
                    )
                    context.refreshTokens.create(
                        userId = userId,
                        deviceId = device.id,
                        jti = jti,
                        tokenHash = Hashing.sha256Hex("${context.config.hashPepper}:$refresh"),
                        expiresAt = Instant.now().plus(context.config.jwt.refreshTtl),
                        ip = call.request.local.remoteHost,
                        userAgent = call.request.headers["User-Agent"]
                    )

                    context.audit.log(
                        action = "device.bind",
                        success = true,
                        actorUserId = userId,
                        actorDeviceId = device.id,
                        targetType = "device",
                        targetId = device.id,
                        detailsJson = "{\"platform\":\"${body.platform}\"}",
                        call = call
                    )

                    call.respond(
                        BindDeviceResponse(
                            deviceId = device.id.toString(),
                            status = device.status,
                            accessToken = access,
                            refreshToken = refresh,
                            expiresIn = context.config.jwt.accessTtl.seconds
                        )
                    )
                }
            }
        }
    }
}
