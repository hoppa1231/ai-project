package com.example.modules.openapi

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.configureOpenApiRoutes() {
    routing {
        get("/openapi.json") {
            val content = this::class.java.classLoader
                .getResource("openapi/openapi.json")
                ?.readText()
                ?: "{}"
            call.respondText(content, ContentType.Application.Json)
        }

        get("/swagger") {
            call.respondRedirect("/swagger/index.html", permanent = false)
        }

        get("/swagger/index.html") {
            val html = """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                  <title>VPN Control Plane API Docs</title>
                  <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css" />
                </head>
                <body>
                  <div id="swagger-ui"></div>
                  <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
                  <script>
                    window.ui = SwaggerUIBundle({
                      url: '/openapi.json',
                      dom_id: '#swagger-ui',
                      deepLinking: true,
                      docExpansion: 'none',
                      displayRequestDuration: true
                    });
                  </script>
                </body>
                </html>
            """.trimIndent()

            call.respondText(html, ContentType.Text.Html)
        }
    }
}
