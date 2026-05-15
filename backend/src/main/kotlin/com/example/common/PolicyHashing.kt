package com.example.common

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class PolicyRuleHashInput(
    val source: String,
    val defaultRuleKey: String?,
    val enabled: Boolean,
    val priority: Int,
    val matchType: String,
    val values: List<String>,
    val action: String
)

object PolicyHashing {
    fun hash(
        defaultRoute: String,
        includeApps: List<String>,
        excludeApps: List<String>,
        includeDomains: List<String>,
        excludeDomains: List<String>,
        routeRules: List<PolicyRuleHashInput> = emptyList()
    ): String {
        val canonical = buildJsonObject {
            put("defaultRoute", defaultRoute)
            putJsonArray("includeApps") { includeApps.sorted().forEach { add(JsonPrimitive(it)) } }
            putJsonArray("excludeApps") { excludeApps.sorted().forEach { add(JsonPrimitive(it)) } }
            putJsonArray("includeDomains") { includeDomains.sorted().forEach { add(JsonPrimitive(it)) } }
            putJsonArray("excludeDomains") { excludeDomains.sorted().forEach { add(JsonPrimitive(it)) } }
            putJsonArray("routeRules") {
                routeRules
                    .sortedWith(
                        compareBy<PolicyRuleHashInput> { it.priority }
                            .thenBy { it.defaultRuleKey.orEmpty() }
                            .thenBy { it.matchType }
                            .thenBy { it.action }
                    )
                    .forEach { rule ->
                        add(
                            buildJsonObject {
                                put("source", rule.source)
                                put("defaultRuleKey", rule.defaultRuleKey ?: "")
                                put("enabled", rule.enabled)
                                put("priority", rule.priority)
                                put("matchType", rule.matchType)
                                putJsonArray("values") {
                                    rule.values.sorted().forEach { add(JsonPrimitive(it)) }
                                }
                                put("action", rule.action)
                            }
                        )
                    }
            }
        }.toString()

        return Hashing.sha256Hex(canonical)
    }
}
