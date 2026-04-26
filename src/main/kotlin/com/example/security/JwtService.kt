package com.example.security

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import com.example.config.JwtConfig
import java.time.Instant
import java.util.Date
import java.util.UUID

data class AccessClaims(
    val userId: UUID,
    val role: String,
    val deviceId: UUID?
)

data class RefreshClaims(
    val userId: UUID,
    val role: String,
    val deviceId: UUID,
    val jti: UUID,
    val expiresAt: Instant
)

class JwtService(private val config: JwtConfig) {
    private val algorithm = Algorithm.HMAC256(config.secret)

    val accessVerifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(config.issuer)
        .withAudience(config.audience)
        .withClaim("typ", "access")
        .build()

    val refreshVerifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(config.issuer)
        .withAudience(config.audience)
        .withClaim("typ", "refresh")
        .build()

    fun issueAccess(claims: AccessClaims): String {
        val now = Instant.now()
        val expiresAt = now.plus(config.accessTtl)

        val builder = JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(claims.userId.toString())
            .withClaim("role", claims.role)
            .withClaim("typ", "access")
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(expiresAt))

        claims.deviceId?.let { builder.withClaim("did", it.toString()) }
        return builder.sign(algorithm)
    }

    fun issueRefresh(claims: AccessClaims): Pair<String, UUID> {
        val now = Instant.now()
        val expiresAt = now.plus(config.refreshTtl)
        val jti = UUID.randomUUID()

        val deviceId = claims.deviceId ?: error("device id is required for refresh token")

        val token = JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(claims.userId.toString())
            .withClaim("role", claims.role)
            .withClaim("did", deviceId.toString())
            .withClaim("typ", "refresh")
            .withJWTId(jti.toString())
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(expiresAt))
            .sign(algorithm)

        return token to jti
    }

    fun verifyRefresh(token: String): RefreshClaims {
        val decoded = refreshVerifier.verify(token)
        val subject = decoded.subject ?: error("missing subject")
        val role = decoded.getClaim("role").asString() ?: error("missing role")
        val deviceId = decoded.getClaim("did").asString() ?: error("missing did")
        val jti = decoded.id ?: error("missing jti")
        val exp = decoded.expiresAtAsInstant ?: error("missing exp")

        return RefreshClaims(
            userId = UUID.fromString(subject),
            role = role,
            deviceId = UUID.fromString(deviceId),
            jti = UUID.fromString(jti),
            expiresAt = exp
        )
    }

    fun decode(token: String): DecodedJWT = JWT.decode(token)
}
