package com.example.db.repo

import com.example.db.ClientEntity
import com.example.db.IssuedConfigEntity
import com.example.db.IssuedConfigHopEntity
import org.jooq.DSLContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class VpnRepository(private val dsl: DSLContext) {
    fun findByIdempotency(userId: UUID, idempotencyKey: String): IssuedConfigEntity? {
        val rec = dsl.fetchOne(
            """
            SELECT id, user_id, device_id, node_id, client_id, route_mode::text AS route_mode, status,
                   vless_uri, config_json::text AS config_json,
                   expires_at, issued_at, revoked_at
            FROM issued_configs
            WHERE user_id = ? AND idempotency_key = ?
            """.trimIndent(),
            userId,
            idempotencyKey
        ) ?: return null

        return mapConfig(rec)
    }

    fun findActiveByDevice(userId: UUID, deviceId: UUID): IssuedConfigEntity? {
        val rec = dsl.fetchOne(
            """
            SELECT id, user_id, device_id, node_id, client_id, route_mode::text AS route_mode, status,
                   vless_uri, config_json::text AS config_json,
                   expires_at, issued_at, revoked_at
            FROM issued_configs
            WHERE user_id = ? AND device_id = ? AND status = 'ISSUED'
            """.trimIndent(),
            userId,
            deviceId
        ) ?: return null

        return mapConfig(rec)
    }

    fun listConfigs(userId: UUID, deviceId: UUID? = null): List<IssuedConfigEntity> {
        val records = if (deviceId == null) {
            dsl.fetch(
                """
                SELECT id, user_id, device_id, node_id, client_id, route_mode::text AS route_mode, status,
                       vless_uri, config_json::text AS config_json,
                       expires_at, issued_at, revoked_at
                FROM issued_configs
                WHERE user_id = ?
                ORDER BY created_at DESC
                """.trimIndent(),
                userId
            )
        } else {
            dsl.fetch(
                """
                SELECT id, user_id, device_id, node_id, client_id, route_mode::text AS route_mode, status,
                       vless_uri, config_json::text AS config_json,
                       expires_at, issued_at, revoked_at
                FROM issued_configs
                WHERE user_id = ? AND device_id = ?
                ORDER BY created_at DESC
                """.trimIndent(),
                userId,
                deviceId
            )
        }

        return records.map(::mapConfig)
    }

    fun insertProvisioning(
        userId: UUID,
        deviceId: UUID,
        nodeId: UUID,
        idempotencyKey: String,
        expiresAt: Instant,
        routeMode: String = "SINGLE"
    ): UUID {
        val rec = dsl.fetchOne(
            """
            INSERT INTO issued_configs (
                user_id, device_id, node_id, idempotency_key,
                route_mode, status, expires_at
            )
            VALUES (?, ?, ?, ?, ?::config_route_mode, 'PROVISIONING', ?::timestamptz)
            RETURNING id
            """.trimIndent(),
            userId,
            deviceId,
            nodeId,
            idempotencyKey,
            routeMode,
            OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC)
        ) ?: error("failed to insert issued config")

        return rec.get("id", UUID::class.java)!!
    }

    fun createClient(
        userId: UUID,
        deviceId: UUID,
        nodeId: UUID,
        email: String,
        vlessUuid: UUID,
        flow: String?,
        expiresAt: Instant
    ): ClientEntity {
        val rec = dsl.fetchOne(
            """
            INSERT INTO clients (
                user_id, device_id, node_id, xray_email,
                vless_uuid, flow, status, expires_at
            )
            VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?::timestamptz)
            RETURNING id, user_id, device_id, node_id, xray_email, vless_uuid, status, flow
            """.trimIndent(),
            userId,
            deviceId,
            nodeId,
            email,
            vlessUuid,
            flow,
            OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC)
        ) ?: error("failed to create client")

        return mapClient(rec)
    }

    fun createConfigHop(
        configId: UUID,
        hopIndex: Int,
        role: String,
        nodeId: UUID,
        clientId: UUID?,
        vlessUuid: UUID,
        flow: String?
    ): IssuedConfigHopEntity {
        val rec = dsl.fetchOne(
            """
            INSERT INTO issued_config_hops (
                config_id, hop_index, role, node_id, client_id,
                vless_uuid, flow, status
            )
            VALUES (?, ?, ?::config_hop_role, ?, ?, ?, ?, 'ACTIVE')
            RETURNING id, config_id, hop_index, role::text AS role, node_id, client_id,
                      vless_uuid, flow, status::text AS status, created_at, revoked_at
            """.trimIndent(),
            configId,
            hopIndex,
            role,
            nodeId,
            clientId,
            vlessUuid,
            flow
        ) ?: error("failed to create config hop")

        return mapConfigHop(rec)
    }

    fun finalizeIssued(
        configId: UUID,
        clientId: UUID,
        vlessUri: String,
        configJson: String,
        configHash: String,
        issuedAt: Instant
    ) {
        dsl.execute(
            """
            UPDATE issued_configs
            SET
                client_id = ?,
                status = 'ISSUED',
                vless_uri = ?,
                config_json = ?::jsonb,
                config_hash = ?,
                issued_at = ?::timestamptz
            WHERE id = ?
            """.trimIndent(),
            clientId,
            vlessUri,
            configJson,
            configHash,
            OffsetDateTime.ofInstant(issuedAt, ZoneOffset.UTC),
            configId
        )
    }

    fun markFailed(configId: UUID, errorCode: String, errorMessage: String) {
        dsl.execute(
            """
            UPDATE issued_configs
            SET status = 'FAILED', error_code = ?, error_message = ?
            WHERE id = ?
            """.trimIndent(),
            errorCode,
            errorMessage,
            configId
        )
    }

    fun findConfigById(userId: UUID, configId: UUID): IssuedConfigEntity? {
        val rec = dsl.fetchOne(
            """
            SELECT id, user_id, device_id, node_id, client_id, route_mode::text AS route_mode, status,
                   vless_uri, config_json::text AS config_json,
                   expires_at, issued_at, revoked_at
            FROM issued_configs
            WHERE user_id = ? AND id = ?
            """.trimIndent(),
            userId,
            configId
        ) ?: return null

        return mapConfig(rec)
    }

    fun listConfigHops(configId: UUID): List<IssuedConfigHopEntity> {
        val records = dsl.fetch(
            """
            SELECT id, config_id, hop_index, role::text AS role, node_id, client_id,
                   vless_uuid, flow, status::text AS status, created_at, revoked_at
            FROM issued_config_hops
            WHERE config_id = ?
            ORDER BY hop_index ASC
            """.trimIndent(),
            configId
        )

        return records.map(::mapConfigHop)
    }

    fun findClientById(clientId: UUID): ClientEntity? {
        val rec = dsl.fetchOne(
            """
            SELECT id, user_id, device_id, node_id, xray_email, vless_uuid, status, flow
            FROM clients
            WHERE id = ?
            """.trimIndent(),
            clientId
        ) ?: return null

        return mapClient(rec)
    }

    fun markRevoking(configId: UUID) {
        dsl.execute(
            "UPDATE issued_configs SET status = 'REVOKING' WHERE id = ? AND status = 'ISSUED'",
            configId
        )
    }

    fun markRevoked(configId: UUID) {
        dsl.execute(
            "UPDATE issued_configs SET status = 'REVOKED', revoked_at = now() WHERE id = ?",
            configId
        )
    }

    fun markConfigHopsRevoked(configId: UUID) {
        dsl.execute(
            """
            UPDATE issued_config_hops
            SET status = 'REVOKED', revoked_at = now()
            WHERE config_id = ? AND status <> 'REVOKED'
            """.trimIndent(),
            configId
        )
    }

    fun markConfigHopsFailed(configId: UUID) {
        dsl.execute(
            """
            UPDATE issued_config_hops
            SET status = 'FAILED'
            WHERE config_id = ? AND status <> 'REVOKED'
            """.trimIndent(),
            configId
        )
    }

    fun revokeClient(clientId: UUID, reason: String) {
        dsl.execute(
            """
            UPDATE clients
            SET status = 'REVOKED', revoked_at = now(), revoke_reason = ?
            WHERE id = ?
            """.trimIndent(),
            reason,
            clientId
        )
    }

    private fun mapConfig(rec: org.jooq.Record): IssuedConfigEntity {
        return IssuedConfigEntity(
            id = rec.get("id", UUID::class.java)!!,
            userId = rec.get("user_id", UUID::class.java)!!,
            deviceId = rec.get("device_id", UUID::class.java)!!,
            nodeId = rec.get("node_id", UUID::class.java)!!,
            clientId = rec.get("client_id", UUID::class.java),
            routeMode = rec.get("route_mode", String::class.java) ?: "SINGLE",
            status = rec.get("status", String::class.java)!!,
            vlessUri = rec.get("vless_uri", String::class.java),
            configJson = rec.get("config_json", String::class.java),
            expiresAt = rec.get("expires_at", OffsetDateTime::class.java)!!.toInstant(),
            issuedAt = rec.get("issued_at", OffsetDateTime::class.java)?.toInstant(),
            revokedAt = rec.get("revoked_at", OffsetDateTime::class.java)?.toInstant()
        )
    }

    private fun mapConfigHop(rec: org.jooq.Record): IssuedConfigHopEntity {
        return IssuedConfigHopEntity(
            id = rec.get("id", UUID::class.java)!!,
            configId = rec.get("config_id", UUID::class.java)!!,
            hopIndex = rec.get("hop_index", Int::class.java)!!,
            role = rec.get("role", String::class.java)!!,
            nodeId = rec.get("node_id", UUID::class.java)!!,
            clientId = rec.get("client_id", UUID::class.java),
            vlessUuid = rec.get("vless_uuid", UUID::class.java)!!,
            flow = rec.get("flow", String::class.java),
            status = rec.get("status", String::class.java)!!,
            createdAt = rec.get("created_at", OffsetDateTime::class.java)!!.toInstant(),
            revokedAt = rec.get("revoked_at", OffsetDateTime::class.java)?.toInstant()
        )
    }

    private fun mapClient(rec: org.jooq.Record): ClientEntity {
        return ClientEntity(
            id = rec.get("id", UUID::class.java)!!,
            userId = rec.get("user_id", UUID::class.java)!!,
            deviceId = rec.get("device_id", UUID::class.java)!!,
            nodeId = rec.get("node_id", UUID::class.java)!!,
            email = rec.get("xray_email", String::class.java)!!,
            vlessUuid = rec.get("vless_uuid", UUID::class.java)!!,
            status = rec.get("status", String::class.java)!!,
            flow = rec.get("flow", String::class.java)
        )
    }
}
