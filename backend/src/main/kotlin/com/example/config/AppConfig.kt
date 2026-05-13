package com.example.config

import io.ktor.server.application.Application
import java.time.Duration

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    val accessTtl: Duration,
    val refreshTtl: Duration
)

data class AppConfig(
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val jwt: JwtConfig,
    val hashPepper: String,
    val xrayMode: String,
    val agentScheme: String,
    val agentBasePath: String,
    val agentToken: String,
    val telegramBotToken: String,
    val telegramBotUsername: String,
    val telegramAppLoginEnabled: Boolean,
    val issueConfigTtl: Duration,
    val freeGbPerMonth: Long
)

fun Application.loadAppConfig(): AppConfig {
    val cfg = environment.config
    return AppConfig(
        dbUrl = cfg.propertyOrNull("app.db.url")?.getString() ?: "jdbc:postgresql://localhost:5432/vpn_cp",
        dbUser = cfg.propertyOrNull("app.db.user")?.getString() ?: "vpn",
        dbPassword = cfg.propertyOrNull("app.db.password")?.getString() ?: "vpn",
        jwt = JwtConfig(
            secret = cfg.propertyOrNull("app.jwt.secret")?.getString() ?: "change-me-in-prod",
            issuer = cfg.propertyOrNull("app.jwt.issuer")?.getString() ?: "vpn-control-plane",
            audience = cfg.propertyOrNull("app.jwt.audience")?.getString() ?: "vpn-client",
            accessTtl = Duration.ofSeconds(cfg.propertyOrNull("app.jwt.accessTtlSeconds")?.getString()?.toLong() ?: 900),
            refreshTtl = Duration.ofDays(cfg.propertyOrNull("app.jwt.refreshTtlDays")?.getString()?.toLong() ?: 30)
        ),
        hashPepper = cfg.propertyOrNull("app.security.hashPepper")?.getString() ?: "change-me-too",
        xrayMode = cfg.propertyOrNull("app.xray.mode")?.getString() ?: "stub",
        agentScheme = cfg.propertyOrNull("app.agent.scheme")?.getString() ?: "http",
        agentBasePath = cfg.propertyOrNull("app.agent.basePath")?.getString() ?: "",
        agentToken = cfg.propertyOrNull("app.agent.token")?.getString() ?: "change-agent-token",
        telegramBotToken = cfg.propertyOrNull("app.telegram.botToken")?.getString() ?: "",
        telegramBotUsername = cfg.propertyOrNull("app.telegram.botUsername")?.getString() ?: "info_panel_85_bot",
        telegramAppLoginEnabled = cfg.propertyOrNull("app.telegram.appLoginEnabled")?.getString()?.toBooleanStrictOrNull() ?: false,
        issueConfigTtl = Duration.ofHours(cfg.propertyOrNull("app.vpn.issueTtlHours")?.getString()?.toLong() ?: 24),
        freeGbPerMonth = cfg.propertyOrNull("app.quota.freeGbPerMonth")?.getString()?.toLong() ?: 10
    )
}
