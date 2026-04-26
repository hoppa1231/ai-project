package com.example.modules.policy

import com.example.common.ApiException
import com.example.common.PolicyHashing
import com.example.config.AppContext
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
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class PolicyResponse(
    val version: Int,
    val defaultRoute: String,
    val includeApps: List<String>,
    val excludeApps: List<String>,
    val includeDomains: List<String>,
    val excludeDomains: List<String>,
    val hash: String,
    val updatedAt: String
)

@Serializable
data class UpdatePolicyRequest(
    val ifVersion: Int,
    val defaultRoute: String,
    val includeApps: List<String> = emptyList(),
    val excludeApps: List<String> = emptyList(),
    val includeDomains: List<String> = emptyList(),
    val excludeDomains: List<String> = emptyList()
)

fun Application.configurePolicyRoutes(context: AppContext) {
    routing {
        authenticate("auth-jwt") {
            route("/policy") {
                get("/current") {
                    val principal = call.principal<JWTPrincipal>()
                        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                    val userId = principal.requireUserId()
                    val policy = context.policies.getCurrent(userId)
                        ?: throw ApiException(HttpStatusCode.NotFound, "POLICY_NOT_FOUND", "Policy not found")
                    call.respond(
                        PolicyResponse(
                            version = policy.version,
                            defaultRoute = policy.defaultRoute,
                            includeApps = policy.includeApps,
                            excludeApps = policy.excludeApps,
                            includeDomains = policy.includeDomains,
                            excludeDomains = policy.excludeDomains,
                            hash = policy.hash,
                            updatedAt = policy.updatedAt.toString()
                        )
                    )
                }

                put("/current") {
                    val principal = call.principal<JWTPrincipal>()
                        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing principal")
                    val userId = principal.requireUserId()
                    val body = call.receive<UpdatePolicyRequest>()

                    val route = body.defaultRoute.uppercase()
                    if (route != "VPN" && route != "DIRECT") {
                        throw ApiException(HttpStatusCode.BadRequest, "INVALID_DEFAULT_ROUTE", "defaultRoute must be VPN or DIRECT")
                    }

                    val hash = PolicyHashing.hash(
                        defaultRoute = route,
                        includeApps = body.includeApps,
                        excludeApps = body.excludeApps,
                        includeDomains = body.includeDomains,
                        excludeDomains = body.excludeDomains
                    )

                    val updated = context.policies.updateCurrent(
                        userId = userId,
                        expectedVersion = body.ifVersion,
                        defaultRoute = route,
                        includeApps = body.includeApps,
                        excludeApps = body.excludeApps,
                        includeDomains = body.includeDomains,
                        excludeDomains = body.excludeDomains,
                        newHash = hash
                    ) ?: throw ApiException(
                        HttpStatusCode.Conflict,
                        "POLICY_VERSION_CONFLICT",
                        "Policy version mismatch"
                    )

                    context.audit.log(
                        action = "policy.update",
                        success = true,
                        actorUserId = userId,
                        actorDeviceId = null,
                        targetType = "policy",
                        targetId = null,
                        detailsJson = "{\"version\":${updated.version}}",
                        call = call
                    )

                    call.respond(
                        PolicyResponse(
                            version = updated.version,
                            defaultRoute = updated.defaultRoute,
                            includeApps = updated.includeApps,
                            excludeApps = updated.excludeApps,
                            includeDomains = updated.includeDomains,
                            excludeDomains = updated.excludeDomains,
                            hash = updated.hash,
                            updatedAt = updated.updatedAt.toString()
                        )
                    )
                }
            }
        }
    }
}
