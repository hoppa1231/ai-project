package com.example.modules.policy

import com.example.common.ApiException
import com.example.common.PolicyHashing
import com.example.common.PolicyRuleHashInput
import com.example.config.AppContext
import com.example.db.RoutingRuleEntity
import com.example.db.repo.PolicyRouteRuleInput
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
import java.net.InetAddress

@Serializable
data class PolicyResponse(
    val version: Int,
    val defaultRoute: String,
    val includeApps: List<String>,
    val excludeApps: List<String>,
    val includeDomains: List<String>,
    val excludeDomains: List<String>,
    val routeRules: List<RouteRuleResponse> = emptyList(),
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
    val excludeDomains: List<String> = emptyList(),
    val routeRules: List<RouteRuleRequest>? = null
)

@Serializable
data class RouteRuleResponse(
    val id: String,
    val source: String,
    val defaultRuleKey: String? = null,
    val name: String,
    val description: String = "",
    val enabled: Boolean,
    val priority: Int,
    val matchType: String,
    val values: List<String>,
    val action: String,
    val editable: Boolean = true
)

@Serializable
data class RouteRuleRequest(
    val id: String? = null,
    val defaultRuleKey: String? = null,
    val name: String,
    val enabled: Boolean = true,
    val priority: Int,
    val matchType: String,
    val values: List<String>,
    val action: String
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
                    val routeRules = context.policies.listEffectiveRouteRules(userId)
                    call.respond(policy.toResponse(routeRules))
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

                    val normalizedRouteRules = body.routeRules?.let { rules ->
                        normalizeRouteRules(context, rules)
                    }
                    val hashRouteRules = normalizedRouteRules?.map { it.toHashInput() }
                        ?: context.policies.listEffectiveRouteRules(userId).map { it.toHashInput() }
                    val hash = PolicyHashing.hash(
                        defaultRoute = route,
                        includeApps = body.includeApps,
                        excludeApps = body.excludeApps,
                        includeDomains = body.includeDomains,
                        excludeDomains = body.excludeDomains,
                        routeRules = hashRouteRules
                    )

                    val updated = context.policies.updateCurrentWithRouteRules(
                        userId = userId,
                        expectedVersion = body.ifVersion,
                        defaultRoute = route,
                        includeApps = body.includeApps,
                        excludeApps = body.excludeApps,
                        includeDomains = body.includeDomains,
                        excludeDomains = body.excludeDomains,
                        newHash = hash,
                        routeRules = normalizedRouteRules
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

                    val routeRules = context.policies.listEffectiveRouteRules(userId)
                    call.respond(updated.toResponse(routeRules))
                }
            }
        }
    }
}

private fun com.example.db.PolicyEntity.toResponse(routeRules: List<RoutingRuleEntity>): PolicyResponse {
    return PolicyResponse(
        version = version,
        defaultRoute = defaultRoute,
        includeApps = includeApps,
        excludeApps = excludeApps,
        includeDomains = includeDomains,
        excludeDomains = excludeDomains,
        routeRules = routeRules.map { it.toResponse() },
        hash = hash,
        updatedAt = updatedAt.toString()
    )
}

private fun RoutingRuleEntity.toResponse(): RouteRuleResponse {
    return RouteRuleResponse(
        id = id.toString(),
        source = source,
        defaultRuleKey = defaultRuleKey,
        name = name,
        description = description,
        enabled = enabled,
        priority = priority,
        matchType = matchType,
        values = values,
        action = action,
        editable = editable
    )
}

private fun normalizeRouteRules(context: AppContext, rules: List<RouteRuleRequest>): List<PolicyRouteRuleInput> {
    if (rules.size > MAX_ROUTE_RULES) {
        throw ApiException(HttpStatusCode.BadRequest, "TOO_MANY_ROUTE_RULES", "Too many routing rules")
    }
    val defaultRules = context.policies.listDefaultRouteRules().associateBy { it.defaultRuleKey }
    val normalized = rules.mapIndexedNotNull { index, rule ->
        val defaultRule = rule.defaultRuleKey?.let { defaultRules[it] }
            ?: rule.defaultRuleKey?.let {
                throw ApiException(HttpStatusCode.BadRequest, "UNKNOWN_DEFAULT_RULE", "Unknown default rule: $it")
            }
        if (defaultRule != null && !defaultRule.editable) {
            throw ApiException(HttpStatusCode.BadRequest, "DEFAULT_RULE_LOCKED", "Default rule cannot be edited")
        }
        val matchType = normalizeMatchType(rule.matchType)
        val action = normalizeAction(rule.action)
        val values = normalizeValues(matchType, rule.values)
        if (defaultRule != null && defaultRule.isSameRule(rule, matchType, values, action)) {
            return@mapIndexedNotNull null
        }
        PolicyRouteRuleInput(
            id = null,
            defaultRuleKey = rule.defaultRuleKey,
            name = rule.name.trim().takeIf { it.isNotBlank() } ?: "Правило ${index + 1}",
            priority = rule.priority.coerceIn(1, 100_000),
            enabled = rule.enabled,
            matchType = matchType,
            values = values,
            action = action
        )
    }
    val defaultOverrideKeys = normalized.mapNotNull { it.defaultRuleKey }
    if (defaultOverrideKeys.size != defaultOverrideKeys.distinct().size) {
        throw ApiException(HttpStatusCode.BadRequest, "DUPLICATE_DEFAULT_OVERRIDE", "Duplicate default rule override")
    }
    return normalized
}

private fun RoutingRuleEntity.isSameRule(
    request: RouteRuleRequest,
    matchType: String,
    values: List<String>,
    action: String
): Boolean {
    return name == request.name.trim() &&
        priority == request.priority.coerceIn(1, 100_000) &&
        enabled == request.enabled &&
        this.matchType == matchType &&
        this.values.toSet() == values.toSet() &&
        this.action == action
}

private fun normalizeMatchType(raw: String): String {
    val value = raw.trim().uppercase()
    if (value in MATCH_TYPES) return value
    throw ApiException(HttpStatusCode.BadRequest, "INVALID_MATCH_TYPE", "Invalid route rule matchType")
}

private fun normalizeAction(raw: String): String {
    val value = raw.trim().uppercase()
    if (value in ACTIONS) return value
    throw ApiException(HttpStatusCode.BadRequest, "INVALID_RULE_ACTION", "Invalid route rule action")
}

private fun normalizeValues(matchType: String, rawValues: List<String>): List<String> {
    if (rawValues.isEmpty() || rawValues.size > MAX_ROUTE_RULE_VALUES) {
        throw ApiException(HttpStatusCode.BadRequest, "INVALID_RULE_VALUES", "Invalid route rule values")
    }
    val values = rawValues.map { it.trim() }
        .filter { it.isNotBlank() }
        .map { value ->
            when (matchType) {
                "DOMAIN_SUFFIX", "DOMAIN_KEYWORD" -> normalizeDomainValue(value)
                "IP_CIDR" -> normalizeCidrValue(value)
                "APP_PACKAGE" -> normalizePackageValue(value)
                else -> value
            }
        }
        .distinct()
    if (values.isEmpty()) {
        throw ApiException(HttpStatusCode.BadRequest, "INVALID_RULE_VALUES", "Invalid route rule values")
    }
    return values
}

private fun normalizeDomainValue(value: String): String {
    val normalized = value.removePrefix(".").lowercase()
    if (normalized.length > 253 || !DOMAIN_VALUE_REGEX.matches(normalized)) {
        throw ApiException(HttpStatusCode.BadRequest, "INVALID_DOMAIN_RULE", "Invalid domain rule value")
    }
    return normalized
}

private fun normalizeCidrValue(value: String): String {
    val parts = value.split('/', limit = 2)
    if (parts.size != 2) {
        throw ApiException(HttpStatusCode.BadRequest, "INVALID_CIDR_RULE", "Invalid CIDR rule value")
    }
    if (!isIpLiteral(parts[0])) {
        throw ApiException(HttpStatusCode.BadRequest, "INVALID_CIDR_RULE", "Invalid CIDR rule value")
    }
    runCatching { InetAddress.getByName(parts[0]) }.getOrElse {
        throw ApiException(HttpStatusCode.BadRequest, "INVALID_CIDR_RULE", "Invalid CIDR rule value")
    }
    val prefix = parts[1].toIntOrNull()
        ?: throw ApiException(HttpStatusCode.BadRequest, "INVALID_CIDR_RULE", "Invalid CIDR rule value")
    val maxPrefix = if (parts[0].contains(':')) 128 else 32
    if (prefix !in 0..maxPrefix) {
        throw ApiException(HttpStatusCode.BadRequest, "INVALID_CIDR_RULE", "Invalid CIDR rule value")
    }
    return "${parts[0]}/$prefix"
}

private fun isIpLiteral(value: String): Boolean {
    if (IPV4_VALUE_REGEX.matches(value)) {
        return value.split('.').all {
            val part = it.toIntOrNull()
            part != null && part in 0..255
        }
    }
    return value.contains(':') && IPV6_VALUE_REGEX.matches(value)
}

private fun normalizePackageValue(value: String): String {
    val normalized = value.lowercase()
    if (!PACKAGE_VALUE_REGEX.matches(normalized)) {
        throw ApiException(HttpStatusCode.BadRequest, "INVALID_PACKAGE_RULE", "Invalid package rule value")
    }
    return normalized
}

private fun PolicyRouteRuleInput.toHashInput(): PolicyRuleHashInput {
    return PolicyRuleHashInput(
        source = if (defaultRuleKey == null) "USER" else "DEFAULT",
        defaultRuleKey = defaultRuleKey,
        enabled = enabled,
        priority = priority,
        matchType = matchType,
        values = values,
        action = action
    )
}

private fun RoutingRuleEntity.toHashInput(): PolicyRuleHashInput {
    return PolicyRuleHashInput(
        source = source,
        defaultRuleKey = defaultRuleKey,
        enabled = enabled,
        priority = priority,
        matchType = matchType,
        values = values,
        action = action
    )
}

private val MATCH_TYPES = setOf("DOMAIN_SUFFIX", "DOMAIN_KEYWORD", "IP_CIDR", "APP_PACKAGE")
private val ACTIONS = setOf("VPN", "DIRECT", "BLOCK")
private val DOMAIN_VALUE_REGEX = Regex("^[a-z0-9*_-]+(\\.[a-z0-9*_-]+)*$")
private val IPV4_VALUE_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
private val IPV6_VALUE_REGEX = Regex("^[0-9a-fA-F:.]+$")
private val PACKAGE_VALUE_REGEX = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
private const val MAX_ROUTE_RULES = 60
private const val MAX_ROUTE_RULE_VALUES = 80
