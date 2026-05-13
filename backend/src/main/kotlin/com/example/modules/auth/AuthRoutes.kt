package com.example.modules.auth

import com.example.common.ApiException
import com.example.common.Hashing
import com.example.common.PolicyHashing
import com.example.config.AppContext
import com.example.security.AccessClaims
import com.example.security.LimitRule
import com.example.security.deviceIdOrNull
import com.example.security.requireUserId
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.exception.DataAccessException
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Serializable
data class RegisterRequest(val email: String, val password: String)

@Serializable
data class LoginRequest(val email: String, val password: String, val deviceId: String? = null)

@Serializable
data class GuestAuthRequest(
    val deviceFingerprint: String,
    val deviceName: String,
    val platform: String,
    val appVersion: String? = null,
    val publicKey: String? = null
)

@Serializable
data class TelegramAuthPayload(
    val id: Long,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    val username: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("auth_date") val authDate: Long,
    val hash: String
)

@Serializable
data class TelegramAuthRequest(
    val telegram: TelegramAuthPayload,
    val deviceFingerprint: String? = null,
    val deviceName: String? = null,
    val platform: String? = null,
    val appVersion: String? = null,
    val publicKey: String? = null
)

@Serializable
data class TelegramAppLoginStartRequest(
    val deviceFingerprint: String,
    val deviceName: String? = null,
    val platform: String? = null,
    val appVersion: String? = null,
    val publicKey: String? = null
)

@Serializable
data class TelegramAppLoginStartResponse(
    val challengeId: String,
    val telegramAppUrl: String,
    val telegramWebUrl: String,
    val expiresIn: Long
)

@Serializable
data class TelegramAppLoginStatusResponse(
    val status: String,
    val auth: AuthResponse? = null,
    val telegram: TelegramAppAccountResponse? = null
)

@Serializable
data class TelegramAppAccountResponse(
    val id: Long,
    val username: String? = null,
    val firstName: String? = null
)

@Serializable
data class UpgradeRequest(val email: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresIn: Long,
    val tokenType: String = "Bearer",
    val deviceBound: Boolean,
    val accountType: String,
    val deviceId: String? = null
)

@Serializable
data class ProfileResponse(
    val userId: String,
    val email: String,
    val role: String,
    val status: String,
    val accountType: String,
    val deviceBound: Boolean,
    val deviceId: String? = null
)

fun Application.configureAuthRoutes(context: AppContext) {
    routing {
        route("/auth") {
            get("/telegram/login") {
                if (context.config.telegramBotToken.isBlank()) {
                    throw ApiException(HttpStatusCode.ServiceUnavailable, "TELEGRAM_AUTH_DISABLED", "Telegram auth is not configured")
                }
                val returnTo = call.request.queryParameters["return_to"]?.takeIf { it.startsWith("securevpn://") }
                    ?: "securevpn://telegram-auth"
                call.respondText(
                    telegramLoginPage(
                        botUsername = context.config.telegramBotUsername.removePrefix("@"),
                        returnTo = returnTo
                    ),
                    ContentType.Text.Html
                )
            }

            post("/telegram/app-login/webhook/{secret}") {
                if (!context.config.telegramAppLoginEnabled || context.config.telegramWebhookSecret.isBlank()) {
                    call.respond(HttpStatusCode.NotFound)
                    return@post
                }
                val secret = call.parameters["secret"]
                if (secret != context.config.telegramWebhookSecret) {
                    call.respond(HttpStatusCode.NotFound)
                    return@post
                }
                context.telegramAppLogins.handleUpdateJson(call.receiveText())
                call.respond(HttpStatusCode.OK)
            }

            post("/register") {
                context.rateLimit.enforce(
                    key = "register:${call.request.local.remoteHost}",
                    rule = LimitRule(10, Duration.ofMinutes(1))
                )

                val body = call.receive<RegisterRequest>()
                validateCreds(body.email, body.password)

                val existing = context.users.findByEmail(body.email)
                if (existing != null) {
                    throw ApiException(HttpStatusCode.Conflict, "EMAIL_ALREADY_EXISTS", "Email already exists")
                }

                val passwordHash = context.passwordHasher.hash(body.password)
                val user = try {
                    context.users.createRegistered(body.email, passwordHash)
                } catch (_: DataAccessException) {
                    throw ApiException(HttpStatusCode.Conflict, "EMAIL_ALREADY_EXISTS", "Email already exists")
                }

                val policyHash = PolicyHashing.hash("VPN", emptyList(), emptyList(), emptyList(), emptyList())
                context.policies.createDefault(user.id, policyHash)

                val access = context.jwt.issueAccess(
                    AccessClaims(userId = user.id, role = user.role, deviceId = null)
                )

                context.audit.log(
                    action = "auth.register",
                    success = true,
                    actorUserId = user.id,
                    actorDeviceId = null,
                    targetType = "user",
                    targetId = user.id,
                    detailsJson = "{\"email\":\"${user.email}\"}",
                    call = call
                )

                call.respond(
                    HttpStatusCode.Created,
                    AuthResponse(
                        accessToken = access,
                        refreshToken = null,
                        expiresIn = context.config.jwt.accessTtl.seconds,
                        deviceBound = false,
                        accountType = user.accountType
                    )
                )
            }

            post("/guest") {
                context.rateLimit.enforce(
                    key = "guest:${call.request.local.remoteHost}",
                    rule = LimitRule(20, Duration.ofMinutes(1))
                )

                val body = call.receive<GuestAuthRequest>()
                if (body.deviceFingerprint.length < 8) {
                    throw ApiException(HttpStatusCode.BadRequest, "INVALID_DEVICE_FINGERPRINT", "Invalid fingerprint")
                }

                val guestEmail = "guest_${UUID.randomUUID().toString().replace("-", "")}@guest.local"
                val guestPasswordHash = context.passwordHasher.hash(UUID.randomUUID().toString())
                val user = context.users.createGuest(guestEmail, guestPasswordHash)

                val policyHash = PolicyHashing.hash("VPN", emptyList(), emptyList(), emptyList(), emptyList())
                context.policies.createDefault(user.id, policyHash)

                val fingerprintHash = Hashing.sha256Hex("${context.config.hashPepper}:${body.deviceFingerprint}")
                val device = context.devices.bindDevice(
                    userId = user.id,
                    fingerprintHash = fingerprintHash,
                    deviceName = body.deviceName,
                    platform = body.platform,
                    appVersion = body.appVersion,
                    publicKey = body.publicKey
                )

                val accessClaims = AccessClaims(userId = user.id, role = user.role, deviceId = device.id)
                val access = context.jwt.issueAccess(accessClaims)
                val (refresh, jti) = context.jwt.issueRefresh(accessClaims)

                context.refreshTokens.create(
                    userId = user.id,
                    deviceId = device.id,
                    jti = jti,
                    tokenHash = Hashing.sha256Hex("${context.config.hashPepper}:$refresh"),
                    expiresAt = Instant.now().plus(context.config.jwt.refreshTtl),
                    ip = call.request.local.remoteHost,
                    userAgent = call.request.headers["User-Agent"]
                )

                context.audit.log(
                    action = "auth.guest",
                    success = true,
                    actorUserId = user.id,
                    actorDeviceId = device.id,
                    targetType = "user",
                    targetId = user.id,
                    detailsJson = "{\"accountType\":\"GUEST\"}",
                    call = call
                )

                call.respond(
                    HttpStatusCode.Created,
                    AuthResponse(
                        accessToken = access,
                        refreshToken = refresh,
                        expiresIn = context.config.jwt.accessTtl.seconds,
                        deviceBound = true,
                        accountType = user.accountType,
                        deviceId = device.id.toString()
                    )
                )
            }

            post("/telegram") {
                context.rateLimit.enforce(
                    key = "telegram:${call.request.local.remoteHost}",
                    rule = LimitRule(30, Duration.ofMinutes(1))
                )

                val body = call.receive<TelegramAuthRequest>()
                val tg = TelegramAuthVerifier.verify(context.config.telegramBotToken, body.telegram)
                call.respond(
                    issueTelegramSession(
                        context = context,
                        tg = tg,
                        deviceFingerprint = body.deviceFingerprint,
                        deviceName = body.deviceName,
                        platform = body.platform,
                        appVersion = body.appVersion,
                        publicKey = body.publicKey,
                        call = call
                    )
                )
            }

            post("/telegram/app-login/start") {
                context.rateLimit.enforce(
                    key = "telegram-app-start:${call.request.local.remoteHost}",
                    rule = LimitRule(20, Duration.ofMinutes(1))
                )
                if (context.config.telegramBotToken.isBlank()) {
                    throw ApiException(HttpStatusCode.ServiceUnavailable, "TELEGRAM_AUTH_DISABLED", "Telegram auth is not configured")
                }
                if (!context.config.telegramAppLoginEnabled) {
                    throw ApiException(HttpStatusCode.ServiceUnavailable, "TELEGRAM_APP_LOGIN_DISABLED", "Telegram app login is disabled")
                }
                val body = call.receive<TelegramAppLoginStartRequest>()
                if (body.deviceFingerprint.length < 8) {
                    throw ApiException(HttpStatusCode.BadRequest, "INVALID_DEVICE_FINGERPRINT", "Invalid fingerprint")
                }
                val challenge = context.telegramAppLogins.create(
                    TelegramAppLoginDevice(
                        fingerprint = body.deviceFingerprint,
                        name = body.deviceName?.takeIf { it.isNotBlank() } ?: "Android",
                        platform = body.platform?.takeIf { it.isNotBlank() } ?: "android",
                        appVersion = body.appVersion,
                        publicKey = body.publicKey
                    )
                )
                call.respond(
                    TelegramAppLoginStartResponse(
                        challengeId = challenge.id,
                        telegramAppUrl = challenge.telegramAppUrl,
                        telegramWebUrl = challenge.telegramWebUrl,
                        expiresIn = Duration.between(Instant.now(), challenge.expiresAt).seconds.coerceAtLeast(0)
                    )
                )
            }

            get("/telegram/app-login/{challengeId}") {
                context.rateLimit.enforce(
                    key = "telegram-app-poll:${call.request.local.remoteHost}",
                    rule = LimitRule(120, Duration.ofMinutes(1))
                )
                val challengeId = call.parameters["challengeId"]
                    ?: throw ApiException(HttpStatusCode.BadRequest, "INVALID_CHALLENGE", "Invalid challenge")
                val pending = context.telegramAppLogins.get(challengeId)
                    ?: throw ApiException(HttpStatusCode.NotFound, "LOGIN_EXPIRED", "Telegram login expired")
                val tg = pending.verified
                if (tg == null) {
                    call.respond(TelegramAppLoginStatusResponse(status = "PENDING"))
                } else {
                    val auth = issueTelegramSession(
                        context = context,
                        tg = tg,
                        deviceFingerprint = pending.device.fingerprint,
                        deviceName = pending.device.name,
                        platform = pending.device.platform,
                        appVersion = pending.device.appVersion,
                        publicKey = pending.device.publicKey,
                        call = call
                    )
                    context.telegramAppLogins.consume(challengeId)
                    call.respond(
                        TelegramAppLoginStatusResponse(
                            status = "READY",
                            auth = auth,
                            telegram = TelegramAppAccountResponse(
                                id = tg.id,
                                username = tg.username,
                                firstName = tg.firstName
                            )
                        )
                    )
                }
            }

            post("/login") {
                context.rateLimit.enforce(
                    key = "login:${call.request.local.remoteHost}",
                    rule = LimitRule(20, Duration.ofMinutes(1))
                )

                val body = call.receive<LoginRequest>()
                val user = context.users.findByEmail(body.email)
                    ?: throw ApiException(HttpStatusCode.Unauthorized, "INVALID_CREDENTIALS", "Invalid credentials")

                if (user.status != "ACTIVE") {
                    throw ApiException(HttpStatusCode.Forbidden, "USER_DISABLED", "User is disabled")
                }

                val validPassword = context.passwordHasher.verify(body.password, user.passwordHash)
                if (!validPassword) {
                    throw ApiException(HttpStatusCode.Unauthorized, "INVALID_CREDENTIALS", "Invalid credentials")
                }

                context.users.touchLastLogin(user.id)

                val deviceId = body.deviceId?.let {
                    runCatching { UUID.fromString(it) }.getOrElse {
                        throw ApiException(HttpStatusCode.BadRequest, "INVALID_DEVICE_ID", "Invalid deviceId")
                    }
                }
                val device = if (deviceId != null) context.devices.findByIdForUser(deviceId, user.id) else null
                val did = if (device?.status == "ENABLED") device.id else null

                val access = context.jwt.issueAccess(
                    AccessClaims(userId = user.id, role = user.role, deviceId = did)
                )

                val refresh: String? = if (did != null) {
                    val (token, jti) = context.jwt.issueRefresh(
                        AccessClaims(userId = user.id, role = user.role, deviceId = did)
                    )
                    context.refreshTokens.create(
                        userId = user.id,
                        deviceId = did,
                        jti = jti,
                        tokenHash = Hashing.sha256Hex("${context.config.hashPepper}:$token"),
                        expiresAt = Instant.now().plus(context.config.jwt.refreshTtl),
                        ip = call.request.local.remoteHost,
                        userAgent = call.request.headers["User-Agent"]
                    )
                    token
                } else null

                context.audit.log(
                    action = "auth.login",
                    success = true,
                    actorUserId = user.id,
                    actorDeviceId = did,
                    targetType = "user",
                    targetId = user.id,
                    detailsJson = buildJsonObject {
                        put("deviceBound", did != null)
                    }.toString(),
                    call = call
                )

                call.respond(
                    AuthResponse(
                        accessToken = access,
                        refreshToken = refresh,
                        expiresIn = context.config.jwt.accessTtl.seconds,
                        deviceBound = did != null,
                        accountType = user.accountType,
                        deviceId = did?.toString()
                    )
                )
            }

            post("/refresh") {
                context.rateLimit.enforce(
                    key = "refresh:${call.request.local.remoteHost}",
                    rule = LimitRule(40, Duration.ofMinutes(1))
                )

                val body = call.receive<RefreshRequest>()
                val claims = try {
                    context.jwt.verifyRefresh(body.refreshToken)
                } catch (_: Exception) {
                    throw ApiException(HttpStatusCode.Unauthorized, "INVALID_REFRESH_TOKEN", "Invalid refresh token")
                }

                val tokenHash = Hashing.sha256Hex("${context.config.hashPepper}:${body.refreshToken}")
                val session = context.refreshTokens.findActive(claims.jti, tokenHash)
                    ?: throw ApiException(HttpStatusCode.Unauthorized, "INVALID_REFRESH_TOKEN", "Invalid refresh token")

                if (session.revokedAt != null || session.expiresAt.isBefore(Instant.now())) {
                    throw ApiException(HttpStatusCode.Unauthorized, "REFRESH_TOKEN_EXPIRED", "Refresh token expired")
                }

                val user = context.users.findById(claims.userId)
                    ?: throw ApiException(HttpStatusCode.Unauthorized, "INVALID_REFRESH_TOKEN", "Invalid refresh token")

                val device = context.devices.findByIdForUser(claims.deviceId, claims.userId)
                    ?: throw ApiException(HttpStatusCode.Unauthorized, "DEVICE_NOT_BOUND", "Device not bound")

                if (device.status != "ENABLED") {
                    throw ApiException(HttpStatusCode.Forbidden, "DEVICE_DISABLED", "Device is disabled")
                }

                val access = context.jwt.issueAccess(
                    AccessClaims(userId = user.id, role = user.role, deviceId = claims.deviceId)
                )
                val (newRefresh, newJti) = context.jwt.issueRefresh(
                    AccessClaims(userId = user.id, role = user.role, deviceId = claims.deviceId)
                )

                context.refreshTokens.create(
                    userId = user.id,
                    deviceId = claims.deviceId,
                    jti = newJti,
                    tokenHash = Hashing.sha256Hex("${context.config.hashPepper}:$newRefresh"),
                    expiresAt = Instant.now().plus(context.config.jwt.refreshTtl),
                    ip = call.request.local.remoteHost,
                    userAgent = call.request.headers["User-Agent"]
                )
                context.refreshTokens.revoke(claims.jti, newJti)

                context.audit.log(
                    action = "auth.refresh",
                    success = true,
                    actorUserId = user.id,
                    actorDeviceId = claims.deviceId,
                    targetType = "refresh_token",
                    targetId = null,
                    detailsJson = "{}",
                    call = call
                )

                call.respond(
                    AuthResponse(
                        accessToken = access,
                        refreshToken = newRefresh,
                        expiresIn = context.config.jwt.accessTtl.seconds,
                        deviceBound = true,
                        accountType = user.accountType,
                        deviceId = claims.deviceId.toString()
                    )
                )
            }
        }

        authenticate("auth-jwt") {
            route("/auth") {
                get("/profile") {
                    val principal = call.principal<JWTPrincipal>()
                        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                    val userId = principal.requireUserId()
                    val user = context.users.findById(userId)
                        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "User not found")
                    val did = principal.deviceIdOrNull()

                    call.respond(
                        ProfileResponse(
                            userId = user.id.toString(),
                            email = user.email,
                            role = user.role,
                            status = user.status,
                            accountType = user.accountType,
                            deviceBound = did != null,
                            deviceId = did?.toString()
                        )
                    )
                }

                post("/upgrade") {
                    val principal = call.principal<JWTPrincipal>()
                        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                    val userId = principal.requireUserId()
                    val did = principal.deviceIdOrNull()
                        ?: throw ApiException(HttpStatusCode.Forbidden, "DEVICE_NOT_BOUND", "Token is not bound to device")

                    val body = call.receive<UpgradeRequest>()
                    validateCreds(body.email, body.password)

                    val current = context.users.findById(userId)
                        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "User not found")
                    if (current.accountType != "GUEST") {
                        throw ApiException(HttpStatusCode.Conflict, "ALREADY_REGISTERED", "User already registered")
                    }

                    val existing = context.users.findByEmail(body.email)
                    if (existing != null && existing.id != userId) {
                        throw ApiException(HttpStatusCode.Conflict, "EMAIL_ALREADY_EXISTS", "Email already exists")
                    }

                    val updated = try {
                        context.users.upgradeGuest(userId, body.email, context.passwordHasher.hash(body.password))
                    } catch (_: DataAccessException) {
                        throw ApiException(HttpStatusCode.Conflict, "EMAIL_ALREADY_EXISTS", "Email already exists")
                    } ?: throw ApiException(HttpStatusCode.Conflict, "ALREADY_REGISTERED", "User already registered")

                    val accessClaims = AccessClaims(userId = updated.id, role = updated.role, deviceId = did)
                    val access = context.jwt.issueAccess(accessClaims)
                    val (refresh, jti) = context.jwt.issueRefresh(accessClaims)

                    context.refreshTokens.create(
                        userId = updated.id,
                        deviceId = did,
                        jti = jti,
                        tokenHash = Hashing.sha256Hex("${context.config.hashPepper}:$refresh"),
                        expiresAt = Instant.now().plus(context.config.jwt.refreshTtl),
                        ip = call.request.local.remoteHost,
                        userAgent = call.request.headers["User-Agent"]
                    )

                    context.audit.log(
                        action = "auth.upgrade",
                        success = true,
                        actorUserId = updated.id,
                        actorDeviceId = did,
                        targetType = "user",
                        targetId = updated.id,
                        detailsJson = "{\"accountType\":\"REGISTERED\"}",
                        call = call
                    )

                    call.respond(
                        AuthResponse(
                            accessToken = access,
                            refreshToken = refresh,
                            expiresIn = context.config.jwt.accessTtl.seconds,
                            deviceBound = true,
                            accountType = updated.accountType,
                            deviceId = did.toString()
                        )
                    )
                }
            }
        }
    }
}

private fun issueTelegramSession(
    context: AppContext,
    tg: VerifiedTelegramAuth,
    deviceFingerprint: String?,
    deviceName: String?,
    platform: String?,
    appVersion: String?,
    publicKey: String?,
    call: io.ktor.server.application.ApplicationCall
): AuthResponse {
    val user = context.users.createTelegram(
        telegramId = tg.id,
        username = tg.username,
        firstName = tg.firstName,
        lastName = tg.lastName,
        photoUrl = tg.photoUrl,
        passwordHash = context.passwordHasher.hash(UUID.randomUUID().toString())
    )

    if (user.status != "ACTIVE") {
        throw ApiException(HttpStatusCode.Forbidden, "USER_DISABLED", "User is disabled")
    }

    val policyHash = PolicyHashing.hash("VPN", emptyList(), emptyList(), emptyList(), emptyList())
    context.policies.createDefault(user.id, policyHash)
    context.users.touchLastLogin(user.id)

    val device = deviceFingerprint?.takeIf { it.isNotBlank() }?.let { fingerprint ->
        if (fingerprint.length < 8) {
            throw ApiException(HttpStatusCode.BadRequest, "INVALID_DEVICE_FINGERPRINT", "Invalid fingerprint")
        }
        context.devices.bindDevice(
            userId = user.id,
            fingerprintHash = Hashing.sha256Hex("${context.config.hashPepper}:$fingerprint"),
            deviceName = deviceName?.takeIf { it.isNotBlank() } ?: "Telegram device",
            platform = platform?.takeIf { it.isNotBlank() } ?: "unknown",
            appVersion = appVersion,
            publicKey = publicKey
        )
    }

    val accessClaims = AccessClaims(userId = user.id, role = user.role, deviceId = device?.id)
    val access = context.jwt.issueAccess(accessClaims)
    val refresh: String? = device?.let {
        val (token, jti) = context.jwt.issueRefresh(accessClaims)
        context.refreshTokens.create(
            userId = user.id,
            deviceId = it.id,
            jti = jti,
            tokenHash = Hashing.sha256Hex("${context.config.hashPepper}:$token"),
            expiresAt = Instant.now().plus(context.config.jwt.refreshTtl),
            ip = call.request.local.remoteHost,
            userAgent = call.request.headers["User-Agent"]
        )
        token
    }

    context.audit.log(
        action = "auth.telegram",
        success = true,
        actorUserId = user.id,
        actorDeviceId = device?.id,
        targetType = "user",
        targetId = user.id,
        detailsJson = buildJsonObject {
            put("telegramId", tg.id)
            put("username", tg.username)
            put("deviceBound", device != null)
        }.toString(),
        call = call
    )

    return AuthResponse(
        accessToken = access,
        refreshToken = refresh,
        expiresIn = context.config.jwt.accessTtl.seconds,
        deviceBound = device != null,
        accountType = user.accountType,
        deviceId = device?.id?.toString()
    )
}

private fun telegramLoginPage(botUsername: String, returnTo: String): String {
    val safeBotUsername = botUsername.replace(Regex("[^A-Za-z0-9_]"), "")
    val safeReturnTo = returnTo.replace("\\", "\\\\").replace("'", "\\'")
    return """
<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Telegram login</title>
  <style>
    html, body { margin: 0; min-height: 100%; background: #f7f2e6; color: #2b2118; font-family: system-ui, sans-serif; }
    body { display: grid; place-items: center; padding: 24px; box-sizing: border-box; }
    main { width: min(420px, 100%); text-align: center; }
    h1 { margin: 0 0 10px; font-size: 22px; }
    p { margin: 10px 0 22px; color: #675c4f; line-height: 1.4; }
  </style>
</head>
<body>
  <main>
    <h1>Вход через Telegram</h1>
    <p>Подтвердите вход, затем мы вернем вас в приложение.</p>
    <script async src="https://telegram.org/js/telegram-widget.js?22"
      data-telegram-login="$safeBotUsername"
      data-size="large"
      data-radius="6"
      data-request-access="write"
      data-onauth="window.location.href='$safeReturnTo?payload=' + encodeURIComponent(JSON.stringify(user));">
    </script>
  </main>
</body>
</html>
    """.trimIndent()
}

private fun validateCreds(email: String, password: String) {
    if (!email.contains('@')) {
        throw ApiException(HttpStatusCode.BadRequest, "INVALID_EMAIL", "Invalid email")
    }
    if (password.length < 8) {
        throw ApiException(HttpStatusCode.BadRequest, "WEAK_PASSWORD", "Password must be at least 8 chars")
    }
}
