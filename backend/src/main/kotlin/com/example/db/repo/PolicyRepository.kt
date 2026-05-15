package com.example.db.repo

import com.example.db.PolicyEntity
import com.example.db.RoutingRuleEntity
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.sql.Array as SqlArray
import java.time.Instant
import java.util.UUID

data class PolicyRouteRuleInput(
    val id: UUID? = null,
    val defaultRuleKey: String? = null,
    val name: String,
    val priority: Int,
    val enabled: Boolean,
    val matchType: String,
    val values: List<String>,
    val action: String
)

class PolicyRepository(private val dsl: DSLContext) {
    fun createDefault(userId: UUID, hash: String) {
        dsl.execute(
            """
            INSERT INTO policies (
                user_id, version, default_route,
                include_apps, exclude_apps, include_domains, exclude_domains,
                policy_hash, status
            )
            VALUES (?, 1, 'VPN', '{}', '{}', '{}', '{}', ?, 'ACTIVE')
            ON CONFLICT (user_id) DO NOTHING
            """.trimIndent(),
            userId,
            hash
        )
    }

    fun getCurrent(userId: UUID): PolicyEntity? {
        val rec = dsl.fetchOne(
            """
            SELECT user_id, version, default_route, include_apps, exclude_apps,
                   include_domains, exclude_domains, policy_hash, updated_at
            FROM policies
            WHERE user_id = ? AND status = 'ACTIVE'
            """.trimIndent(),
            userId
        ) ?: return null

        return mapPolicy(rec)
    }

    fun listDefaultRouteRules(): List<RoutingRuleEntity> {
        return dsl.fetch(
            """
            SELECT id, rule_key, name, description, priority, enabled,
                   match_type, match_values, action, editable, revision
            FROM routing_default_rules
            ORDER BY priority ASC, rule_key ASC
            """.trimIndent()
        ).map { rec ->
            RoutingRuleEntity(
                id = rec.get("id", UUID::class.java)!!,
                source = "DEFAULT",
                userId = null,
                defaultRuleKey = rec.get("rule_key", String::class.java)!!,
                name = rec.get("name", String::class.java)!!,
                description = rec.get("description", String::class.java)!!,
                priority = rec.get("priority", Int::class.java)!!,
                enabled = rec.get("enabled", Boolean::class.java)!!,
                matchType = rec.get("match_type", String::class.java)!!,
                values = toStringList(rec.get("match_values", SqlArray::class.java)),
                action = rec.get("action", String::class.java)!!,
                editable = rec.get("editable", Boolean::class.java)!!,
                revision = rec.get("revision", Int::class.java)!!
            )
        }
    }

    fun listUserRouteRules(userId: UUID): List<RoutingRuleEntity> {
        return dsl.fetch(
            """
            SELECT id, user_id, default_rule_key, name, priority, enabled,
                   match_type, match_values, action
            FROM user_route_rules
            WHERE user_id = ?
            ORDER BY priority ASC, id ASC
            """.trimIndent(),
            userId
        ).map { rec ->
            RoutingRuleEntity(
                id = rec.get("id", UUID::class.java)!!,
                source = "USER",
                userId = rec.get("user_id", UUID::class.java)!!,
                defaultRuleKey = rec.get("default_rule_key", String::class.java),
                name = rec.get("name", String::class.java)!!,
                description = "",
                priority = rec.get("priority", Int::class.java)!!,
                enabled = rec.get("enabled", Boolean::class.java)!!,
                matchType = rec.get("match_type", String::class.java)!!,
                values = toStringList(rec.get("match_values", SqlArray::class.java)),
                action = rec.get("action", String::class.java)!!,
                editable = true,
                revision = 1
            )
        }
    }

    fun listEffectiveRouteRules(userId: UUID): List<RoutingRuleEntity> {
        val defaults = listDefaultRouteRules()
        val userRules = listUserRouteRules(userId)
        val overrides = userRules
            .filter { it.defaultRuleKey != null }
            .associateBy { it.defaultRuleKey }
        val customRules = userRules.filter { it.defaultRuleKey == null }

        val defaultRules = defaults.map { defaultRule ->
            val override = overrides[defaultRule.defaultRuleKey] ?: return@map defaultRule
            defaultRule.copy(
                id = override.id,
                userId = userId,
                name = override.name,
                priority = override.priority,
                enabled = override.enabled,
                matchType = override.matchType,
                values = override.values,
                action = override.action
            )
        }

        return (defaultRules + customRules)
            .sortedWith(compareBy<RoutingRuleEntity> { it.priority }.thenBy { it.name })
    }

    fun updateCurrent(
        userId: UUID,
        expectedVersion: Int,
        defaultRoute: String,
        includeApps: List<String>,
        excludeApps: List<String>,
        includeDomains: List<String>,
        excludeDomains: List<String>,
        newHash: String
    ): PolicyEntity? {
        return updateCurrentWithRouteRules(
            userId = userId,
            expectedVersion = expectedVersion,
            defaultRoute = defaultRoute,
            includeApps = includeApps,
            excludeApps = excludeApps,
            includeDomains = includeDomains,
            excludeDomains = excludeDomains,
            newHash = newHash,
            routeRules = null
        )
    }

    fun updateCurrentWithRouteRules(
        userId: UUID,
        expectedVersion: Int,
        defaultRoute: String,
        includeApps: List<String>,
        excludeApps: List<String>,
        includeDomains: List<String>,
        excludeDomains: List<String>,
        newHash: String,
        routeRules: List<PolicyRouteRuleInput>?
    ): PolicyEntity? {
        return dsl.transactionResult { configuration ->
            val tx = DSL.using(configuration)
            val updated = tx.execute(
                """
                UPDATE policies
                SET
                    version = version + 1,
                    default_route = ?::default_route,
                    include_apps = ?::text[],
                    exclude_apps = ?::text[],
                    include_domains = ?::text[],
                    exclude_domains = ?::text[],
                    policy_hash = ?,
                    updated_at = now()
                WHERE user_id = ? AND version = ? AND status = 'ACTIVE'
                """.trimIndent(),
                defaultRoute,
                toPgTextArray(includeApps),
                toPgTextArray(excludeApps),
                toPgTextArray(includeDomains),
                toPgTextArray(excludeDomains),
                newHash,
                userId,
                expectedVersion
            )

            if (updated == 0) {
                null
            } else {
                if (routeRules != null) {
                    tx.execute("DELETE FROM user_route_rules WHERE user_id = ?", userId)
                    routeRules.forEach { rule ->
                        tx.execute(
                            """
                            INSERT INTO user_route_rules (
                                id, user_id, default_rule_key, name, priority,
                                enabled, match_type, match_values, action
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?::text[], ?)
                            """.trimIndent(),
                            rule.id ?: UUID.randomUUID(),
                            userId,
                            rule.defaultRuleKey,
                            rule.name,
                            rule.priority,
                            rule.enabled,
                            rule.matchType,
                            toPgTextArray(rule.values),
                            rule.action
                        )
                    }
                }

                fetchCurrent(tx, userId)
            }
        }
    }

    private fun fetchCurrent(tx: DSLContext, userId: UUID): PolicyEntity? {
        val rec = tx.fetchOne(
            """
            SELECT user_id, version, default_route, include_apps, exclude_apps,
                   include_domains, exclude_domains, policy_hash, updated_at
            FROM policies
            WHERE user_id = ? AND status = 'ACTIVE'
            """.trimIndent(),
            userId
        ) ?: return null

        return mapPolicy(rec)
    }

    private fun mapPolicy(rec: org.jooq.Record): PolicyEntity {
        return PolicyEntity(
            userId = rec.get("user_id", UUID::class.java)!!,
            version = rec.get("version", Int::class.java)!!,
            defaultRoute = rec.get("default_route", String::class.java)!!,
            includeApps = toStringList(rec.get("include_apps", SqlArray::class.java)),
            excludeApps = toStringList(rec.get("exclude_apps", SqlArray::class.java)),
            includeDomains = toStringList(rec.get("include_domains", SqlArray::class.java)),
            excludeDomains = toStringList(rec.get("exclude_domains", SqlArray::class.java)),
            hash = rec.get("policy_hash", String::class.java)!!,
            updatedAt = rec.get("updated_at", java.time.OffsetDateTime::class.java)!!.toInstant()
        )
    }

    private fun toStringList(sqlArray: SqlArray?): List<String> {
        if (sqlArray == null) return emptyList()
        val raw = sqlArray.array as? Array<*> ?: return emptyList()
        return raw.mapNotNull { it?.toString() }
    }

    private fun toPgTextArray(values: List<String>): String {
        return values.joinToString(prefix = "{", postfix = "}") { v ->
            val escaped = v.replace("\\", "\\\\").replace("\"", "\\\"")
            "\"$escaped\""
        }
    }
}
