package com.example.common

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

object PolicyHashing {
    fun hash(
        defaultRoute: String,
        includeApps: List<String>,
        excludeApps: List<String>,
        includeDomains: List<String>,
        excludeDomains: List<String>
    ): String {
        val canonical = buildJsonObject {
            put("defaultRoute", defaultRoute)
            putJsonArray("includeApps") { includeApps.sorted().forEach { add(JsonPrimitive(it)) } }
            putJsonArray("excludeApps") { excludeApps.sorted().forEach { add(JsonPrimitive(it)) } }
            putJsonArray("includeDomains") { includeDomains.sorted().forEach { add(JsonPrimitive(it)) } }
            putJsonArray("excludeDomains") { excludeDomains.sorted().forEach { add(JsonPrimitive(it)) } }
        }.toString()

        return Hashing.sha256Hex(canonical)
    }
}
