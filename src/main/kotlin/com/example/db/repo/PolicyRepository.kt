package com.example.db.repo

import com.example.db.PolicyEntity
import org.jooq.DSLContext
import java.sql.Array as SqlArray
import java.time.Instant
import java.util.UUID

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
        val updated = dsl.execute(
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

        if (updated == 0) return null
        return getCurrent(userId)
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
