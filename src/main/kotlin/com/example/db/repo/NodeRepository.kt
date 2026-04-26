package com.example.db.repo

import com.example.db.NodeEntity
import com.example.db.PublicNode
import org.jooq.DSLContext
import java.sql.Array as SqlArray
import java.util.UUID

data class CreateNodeParams(
    val name: String,
    val region: String,
    val countryCode: String,
    val hostname: String,
    val publicAddress: String,
    val publicPort: Int,
    val apiHost: String,
    val apiPort: Int,
    val inboundTag: String,
    val realityServerName: String,
    val realityPublicKey: String,
    val realityShortId: String,
    val realityFingerprint: String,
    val realityAlpn: List<String>,
    val weight: Int,
    val maxClients: Int
)

data class UpdateNodeParams(
    val status: String? = null,
    val weight: Int? = null,
    val maxClients: Int? = null
)

class NodeRepository(private val dsl: DSLContext) {
    fun listPublic(region: String?): List<PublicNode> {
        val records = if (region.isNullOrBlank()) {
            dsl.fetch(
                """
                SELECT n.id, n.name, n.region, n.country_code, n.hostname, n.public_port, n.health,
                       n.reality_fingerprint, n.reality_alpn,
                       COALESCE(c.cnt, 0) AS load
                FROM nodes n
                LEFT JOIN (
                    SELECT node_id, COUNT(*) AS cnt
                    FROM clients
                    WHERE status = 'ACTIVE'
                    GROUP BY node_id
                ) c ON c.node_id = n.id
                WHERE n.status = 'ACTIVE' AND n.health = 'HEALTHY'
                ORDER BY n.weight DESC, COALESCE(c.cnt, 0) ASC
                """.trimIndent()
            )
        } else {
            dsl.fetch(
                """
                SELECT n.id, n.name, n.region, n.country_code, n.hostname, n.public_port, n.health,
                       n.reality_fingerprint, n.reality_alpn,
                       COALESCE(c.cnt, 0) AS load
                FROM nodes n
                LEFT JOIN (
                    SELECT node_id, COUNT(*) AS cnt
                    FROM clients
                    WHERE status = 'ACTIVE'
                    GROUP BY node_id
                ) c ON c.node_id = n.id
                WHERE n.status = 'ACTIVE' AND n.health = 'HEALTHY' AND n.region = ?
                ORDER BY n.weight DESC, COALESCE(c.cnt, 0) ASC
                """.trimIndent(),
                region
            )
        }

        return records.map {
            PublicNode(
                id = it.get("id", UUID::class.java)!!,
                name = it.get("name", String::class.java)!!,
                region = it.get("region", String::class.java)!!,
                countryCode = it.get("country_code", String::class.java)!!,
                hostname = it.get("hostname", String::class.java)!!,
                port = it.get("public_port", Int::class.java)!!,
                health = it.get("health", String::class.java)!!,
                load = (it.get("load", Number::class.java) ?: 0).toInt(),
                fingerprint = it.get("reality_fingerprint", String::class.java)!!,
                alpn = toStringList(it.get("reality_alpn", SqlArray::class.java))
            )
        }
    }

    fun pickNode(region: String?): NodeEntity? {
        val rec = if (region.isNullOrBlank()) {
            dsl.fetchOne(
                """
                SELECT n.*, COALESCE(c.cnt, 0) AS load
                FROM nodes n
                LEFT JOIN (
                    SELECT node_id, COUNT(*) AS cnt
                    FROM clients
                    WHERE status = 'ACTIVE'
                    GROUP BY node_id
                ) c ON c.node_id = n.id
                WHERE n.status = 'ACTIVE' AND n.health = 'HEALTHY'
                ORDER BY n.weight DESC, COALESCE(c.cnt, 0) ASC
                LIMIT 1
                """.trimIndent()
            )
        } else {
            dsl.fetchOne(
                """
                SELECT n.*, COALESCE(c.cnt, 0) AS load
                FROM nodes n
                LEFT JOIN (
                    SELECT node_id, COUNT(*) AS cnt
                    FROM clients
                    WHERE status = 'ACTIVE'
                    GROUP BY node_id
                ) c ON c.node_id = n.id
                WHERE n.status = 'ACTIVE' AND n.health = 'HEALTHY' AND n.region = ?
                ORDER BY n.weight DESC, COALESCE(c.cnt, 0) ASC
                LIMIT 1
                """.trimIndent(),
                region
            )
        } ?: return null

        return mapNode(rec)
    }

    fun findById(id: UUID): NodeEntity? {
        val rec = dsl.fetchOne(
            "SELECT n.*, 0 AS load FROM nodes n WHERE id = ?",
            id
        ) ?: return null
        return mapNode(rec)
    }

    fun create(params: CreateNodeParams): NodeEntity {
        val rec = dsl.fetchOne(
            """
            INSERT INTO nodes (
                name, region, country_code, hostname, public_address, public_port,
                api_host, api_port, inbound_tag,
                reality_server_name, reality_public_key, reality_short_id,
                reality_fingerprint, reality_alpn, weight, max_clients,
                status, health
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?, 'ACTIVE', 'UNKNOWN')
            RETURNING *, 0 AS load
            """.trimIndent(),
            params.name,
            params.region,
            params.countryCode,
            params.hostname,
            params.publicAddress,
            params.publicPort,
            params.apiHost,
            params.apiPort,
            params.inboundTag,
            params.realityServerName,
            params.realityPublicKey,
            params.realityShortId,
            params.realityFingerprint,
            toPgTextArray(params.realityAlpn),
            params.weight,
            params.maxClients
        ) ?: error("failed to insert node")

        return mapNode(rec)
    }

    fun update(id: UUID, params: UpdateNodeParams): NodeEntity? {
        val updated = dsl.execute(
            """
            UPDATE nodes
            SET
                status = COALESCE(?, status::text)::node_status,
                weight = COALESCE(?, weight),
                max_clients = COALESCE(?, max_clients)
            WHERE id = ?
            """.trimIndent(),
            params.status,
            params.weight,
            params.maxClients,
            id
        )

        if (updated == 0) return null
        return findById(id)
    }

    fun updateHealth(id: UUID, health: String, message: String?, latencyMs: Int?) {
        dsl.execute(
            """
            UPDATE nodes
            SET health = ?::node_health,
                health_message = ?,
                latency_ms = ?,
                last_health_check_at = now()
            WHERE id = ?
            """.trimIndent(),
            health,
            message,
            latencyMs,
            id
        )
    }

    fun listAll(): List<NodeEntity> {
        val records = dsl.fetch(
            """
            SELECT n.*, COALESCE(c.cnt, 0) AS load
            FROM nodes n
            LEFT JOIN (
                SELECT node_id, COUNT(*) AS cnt
                FROM clients
                WHERE status = 'ACTIVE'
                GROUP BY node_id
            ) c ON c.node_id = n.id
            ORDER BY n.created_at DESC
            """.trimIndent()
        )

        return records.map(::mapNode)
    }

    private fun mapNode(rec: org.jooq.Record): NodeEntity {
        return NodeEntity(
            id = rec.get("id", UUID::class.java)!!,
            name = rec.get("name", String::class.java)!!,
            region = rec.get("region", String::class.java)!!,
            countryCode = rec.get("country_code", String::class.java)!!,
            hostname = rec.get("hostname", String::class.java)!!,
            publicAddress = rec.get("public_address", String::class.java)!!,
            publicPort = rec.get("public_port", Int::class.java)!!,
            inboundTag = rec.get("inbound_tag", String::class.java)!!,
            realityServerName = rec.get("reality_server_name", String::class.java)!!,
            realityPublicKey = rec.get("reality_public_key", String::class.java)!!,
            realityShortId = rec.get("reality_short_id", String::class.java)!!,
            realityFingerprint = rec.get("reality_fingerprint", String::class.java)!!,
            realityAlpn = toStringList(rec.get("reality_alpn", SqlArray::class.java)),
            status = rec.get("status", String::class.java)!!,
            health = rec.get("health", String::class.java)!!,
            weight = rec.get("weight", Int::class.java)!!,
            load = (rec.get("load", Number::class.java) ?: 0).toInt(),
            maxClients = rec.get("max_clients", Int::class.java)!!
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
