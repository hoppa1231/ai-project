package com.example

import com.example.bootstrap.BootstrapSeeder
import com.example.common.ApiError
import com.example.common.ApiException
import com.example.config.AppContext
import com.example.config.buildContext
import com.example.config.loadAppConfig
import com.example.modules.admin.configureAdminRoutes
import com.example.modules.auth.configureAuthRoutes
import com.example.modules.auth.startTelegramAppLoginWorker
import com.example.modules.devices.configureDeviceRoutes
import com.example.modules.health.configureHealthRoutes
import com.example.modules.health.startNodeHealthWorker
import com.example.modules.nodes.configureNodeRoutes
import com.example.modules.notifications.configureNotificationRoutes
import com.example.modules.openapi.configureOpenApiRoutes
import com.example.modules.policy.configurePolicyRoutes
import com.example.modules.quota.configureQuotaRoutes
import com.example.modules.vpn.configureVpnRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

val AppContextKey = AttributeKey<AppContext>("app-context")

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    val context = buildContext(loadAppConfig())
    attributes.put(AppContextKey, context)
    BootstrapSeeder.run(context)

    install(CallLogging) {
        level = Level.INFO
    }

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(
                cause.status,
                ApiError(
                    code = cause.code,
                    message = cause.message,
                    details = cause.details,
                    requestId = call.request.headers["X-Request-Id"]
                )
            )
        }
        exception<Throwable> { call, cause ->
            this@module.environment.log.error("Unhandled error", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError(
                    code = "INTERNAL_ERROR",
                    message = "Internal server error",
                    requestId = call.request.headers["X-Request-Id"]
                )
            )
        }
    }

    install(Authentication) {
        jwt("auth-jwt") {
            verifier(context.jwt.accessVerifier)
            validate { credential ->
                val typ = credential.payload.getClaim("typ").asString()
                if (typ == "access") JWTPrincipal(credential.payload) else null
            }
        }
    }

    configureAuthRoutes(context)
    configureDeviceRoutes(context)
    configureNodeRoutes(context)
    configureVpnRoutes(context)
    configurePolicyRoutes(context)
    configureQuotaRoutes(context)
    configureNotificationRoutes(context)
    configureAdminRoutes(context)
    configureHealthRoutes(context)
    configureOpenApiRoutes()

    startNodeHealthWorker(context)
    if (context.config.telegramAppLoginEnabled && context.config.telegramWebhookSecret.isBlank()) {
        startTelegramAppLoginWorker(context)
    }
}

fun Application.appContext(): AppContext = attributes[AppContextKey]
