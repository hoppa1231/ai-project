package com.example.modules.users

import com.example.common.ApiException
import com.example.config.AppContext
import com.example.security.requireRole
import com.example.security.requireUserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class UserResponse(
    val id: String,
    val email: String,
    val role: String,
    val status: String,
    val accountType: String
)

fun Application.configureUserRoutes(context: AppContext) {
    routing {
        authenticate("auth-jwt") {
            route("/users") {
                get("/me") {
                    val principal = call.principal<JWTPrincipal>()
                        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                    val userId = principal.requireUserId()
                    val user = context.users.findById(userId)
                        ?: throw ApiException(HttpStatusCode.NotFound, "USER_NOT_FOUND", "User not found")

                    call.respond(
                        UserResponse(
                            id = user.id.toString(),
                            email = user.email,
                            role = user.role,
                            status = user.status,
                            accountType = user.accountType
                        )
                    )
                }

                get {
                    val principal = call.principal<JWTPrincipal>()
                        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")

                    if (principal.requireRole() != "ADMIN") {
                        throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "Admin role required")
                    }

                    val users = context.users.findAll()
                    call.respond(users.map {
                        UserResponse(
                            id = it.id.toString(),
                            email = it.email,
                            role = it.role,
                            status = it.status,
                            accountType = it.accountType
                        )
                    })
                }
            }
        }
    }
}
