package com.example

import com.asyncapi.kotlinasyncapi.context.service.AsyncApiExtension
import com.asyncapi.kotlinasyncapi.ktor.AsyncApiPlugin
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.application.*
import io.ktor.server.plugins.openapi.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.routing.*
import io.ktor.server.response.*

fun Application.configureHTTP() {
    install(AsyncApiPlugin) {
        extension = AsyncApiExtension.builder {
            info {
                title("Sample API")
                version("1.0.0")
            }
        }
    }

    // Маршруты для документации
    routing {
        // OpenAPI с настройками
        openAPI(path = "openapi") {
            info = OpenApiInfo(
                title = "My API",
                version = "1.0.0"
            )
        }

        // Swagger UI с настройками
        swaggerUI(path = "/swagger") {
            info = OpenApiInfo(
                title = "My API",
                version = "1.0.0"
            )
        }
    }
}