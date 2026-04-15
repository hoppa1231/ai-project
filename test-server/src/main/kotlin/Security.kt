package com.example

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.cors.routing.*
import java.util.Date

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    val realm: String,
    val accessTokenExpiry: Long,
    val refreshTokenExpiry: Long
)

fun Application.jwtConfig(): JwtConfig = JwtConfig(
    secret           = environment.config.property("jwt.secret").getString(),
    issuer           = environment.config.property("jwt.issuer").getString(),
    audience         = environment.config.property("jwt.audience").getString(),
    realm            = environment.config.property("jwt.realm").getString(),
    accessTokenExpiry  = environment.config.property("jwt.accessTokenExpiry").getString().toLong(),
    refreshTokenExpiry = environment.config.property("jwt.refreshTokenExpiry").getString().toLong()
)

fun Application.configureSecurity() {
    val cfg = jwtConfig()
    val algorithm = Algorithm.HMAC256(cfg.secret)

    install(Authentication) {
        jwt("auth-jwt") {
            realm = cfg.realm
            verifier(
                JWT.require(algorithm)
                    .withIssuer(cfg.issuer)
                    .withAudience(cfg.audience)
                    .build()
            )
            validate { credential ->
                if (credential.payload.getClaim("userId").asInt() != null)
                    JWTPrincipal(credential.payload)
                else null
            }
        }
    }
}

fun Application.configureCors() {
    install(CORS) {
        anyHost()                              // Домен который может обращаться к API
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
    }
}

// Утилиты для генерации токенов
fun generateAccessToken(cfg: JwtConfig, userId: Int, login: String): String =
    JWT.create()
        .withIssuer(cfg.issuer)
        .withAudience(cfg.audience)
        .withClaim("userId", userId)
        .withClaim("login", login)
        .withExpiresAt(Date(System.currentTimeMillis() + cfg.accessTokenExpiry))
        .sign(Algorithm.HMAC256(cfg.secret))

fun generateRefreshToken(cfg: JwtConfig, userId: Int): String =
    JWT.create()
        .withIssuer(cfg.issuer)
        .withAudience(cfg.audience)
        .withClaim("userId", userId)
        .withClaim("type", "refresh")
        .withExpiresAt(Date(System.currentTimeMillis() + cfg.refreshTokenExpiry))
        .sign(Algorithm.HMAC256(cfg.secret))
