package com.example.db.repo

import com.example.db.NodeClientInventoryEntity
import com.example.xray.XrayNodeClient
import org.jooq.DSLContext
import java.time.OffsetDateTime
import java.util.UUID

class NodeClientInventoryRepository(private val dsl: DSLContext) {
    fun upsertSnapshot(nodeId: UUID, clients: List<XrayNodeClient>): Int {
        val emails = clients.map { it.email }
        if (emails.isEmpty()) {
            dsl.execute("DELETE FROM node_client_inventory WHERE node_id = ?", nodeId)
            return 0
        }
        val changed = clients.sumOf { client ->
            dsl.execute(
                """
                INSERT INTO node_client_inventory (
                    node_id, inbound_id, inbound_remark, inbound_tag, xray_email,
                    vless_uuid, flow, enabled, total_bytes, up_bytes, down_bytes,
                    expiry_time, limit_ip, sub_id, telegram_id, last_synced_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (node_id, xray_email) DO UPDATE SET
                    inbound_id = EXCLUDED.inbound_id,
                    inbound_remark = EXCLUDED.inbound_remark,
                    inbound_tag = EXCLUDED.inbound_tag,
                    vless_uuid = EXCLUDED.vless_uuid,
                    flow = EXCLUDED.flow,
                    enabled = EXCLUDED.enabled,
                    total_bytes = EXCLUDED.total_bytes,
                    up_bytes = EXCLUDED.up_bytes,
                    down_bytes = EXCLUDED.down_bytes,
                    expiry_time = EXCLUDED.expiry_time,
                    limit_ip = EXCLUDED.limit_ip,
                    sub_id = EXCLUDED.sub_id,
                    telegram_id = EXCLUDED.telegram_id,
                    last_synced_at = now()
                """.trimIndent(),
                nodeId,
                client.inboundId,
                client.inboundRemark,
                client.inboundTag,
                client.email,
                client.uuid,
                client.flow,
                client.enabled,
                client.totalBytes,
                client.upBytes,
                client.downBytes,
                client.expiryTime,
                client.limitIp,
                client.subId,
                client.telegramId
            )
        }
        dsl.execute(
            "DELETE FROM node_client_inventory WHERE node_id = ? AND xray_email <> ALL(?::text[])",
            nodeId,
            toPgTextArray(emails)
        )
        return changed
    }

    fun listByNode(nodeId: UUID): List<NodeClientInventoryEntity> {
        return dsl.fetch(
            """
            SELECT id, node_id, inbound_id, inbound_remark, inbound_tag, xray_email,
                   vless_uuid, flow, enabled, total_bytes, up_bytes, down_bytes,
                   expiry_time, limit_ip, sub_id, telegram_id, last_synced_at
            FROM node_client_inventory
            WHERE node_id = ?
            ORDER BY inbound_id ASC, xray_email ASC
            """.trimIndent(),
            nodeId
        ).map(::map)
    }

    fun findByNodeAndEmail(nodeId: UUID, email: String): NodeClientInventoryEntity? {
        return dsl.fetchOne(
            """
            SELECT id, node_id, inbound_id, inbound_remark, inbound_tag, xray_email,
                   vless_uuid, flow, enabled, total_bytes, up_bytes, down_bytes,
                   expiry_time, limit_ip, sub_id, telegram_id, last_synced_at
            FROM node_client_inventory
            WHERE node_id = ? AND xray_email = ?
            """.trimIndent(),
            nodeId,
            email
        )?.let(::map)
    }

    fun listByTelegramId(telegramId: Long): List<NodeClientInventoryEntity> {
        return dsl.fetch(
            """
            SELECT id, node_id, inbound_id, inbound_remark, inbound_tag, xray_email,
                   vless_uuid, flow, enabled, total_bytes, up_bytes, down_bytes,
                   expiry_time, limit_ip, sub_id, telegram_id, last_synced_at
            FROM node_client_inventory
            WHERE telegram_id = ?
            ORDER BY last_synced_at DESC, xray_email ASC
            """.trimIndent(),
            telegramId
        ).map(::map)
    }

    fun listAll(): List<NodeClientInventoryEntity> {
        return dsl.fetch(
            """
            SELECT id, node_id, inbound_id, inbound_remark, inbound_tag, xray_email,
                   vless_uuid, flow, enabled, total_bytes, up_bytes, down_bytes,
                   expiry_time, limit_ip, sub_id, telegram_id, last_synced_at
            FROM node_client_inventory
            ORDER BY last_synced_at DESC, telegram_id NULLS LAST, xray_email ASC
            """.trimIndent()
        ).map(::map)
    }

    private fun map(rec: org.jooq.Record): NodeClientInventoryEntity {
        return NodeClientInventoryEntity(
            id = rec.get("id", UUID::class.java)!!,
            nodeId = rec.get("node_id", UUID::class.java)!!,
            inboundId = rec.get("inbound_id", Int::class.java)!!,
            inboundRemark = rec.get("inbound_remark", String::class.java)!!,
            inboundTag = rec.get("inbound_tag", String::class.java)!!,
            email = rec.get("xray_email", String::class.java)!!,
            uuid = rec.get("vless_uuid", String::class.java),
            flow = rec.get("flow", String::class.java),
            enabled = rec.get("enabled", Boolean::class.java)!!,
            totalBytes = rec.get("total_bytes", Long::class.java)!!,
            upBytes = rec.get("up_bytes", Long::class.java)!!,
            downBytes = rec.get("down_bytes", Long::class.java)!!,
            expiryTime = rec.get("expiry_time", Long::class.java)!!,
            limitIp = rec.get("limit_ip", Int::class.java)!!,
            subId = rec.get("sub_id", String::class.java),
            telegramId = rec.get("telegram_id", Long::class.java),
            lastSyncedAt = rec.get("last_synced_at", OffsetDateTime::class.java)!!.toInstant()
        )
    }

    private fun toPgTextArray(values: List<String>): String {
        return values.joinToString(prefix = "{", postfix = "}") { v ->
            val escaped = v.replace("\\", "\\\\").replace("\"", "\\\"")
            "\"$escaped\""
        }
    }
}
