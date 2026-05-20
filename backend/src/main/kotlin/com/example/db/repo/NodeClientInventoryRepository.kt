package com.example.db.repo

import com.example.db.NodeClientInventoryEntity
import com.example.xray.XrayNodeClient
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class NodeClientInventoryRepository(private val dsl: DSLContext) {
    fun upsertSnapshot(nodeId: UUID, clients: List<XrayNodeClient>): Int {
        return dsl.transactionResult { cfg ->
            val tx = DSL.using(cfg)
            val emails = clients.map { it.email }
            if (emails.isEmpty()) {
                tx.execute("DELETE FROM node_client_inventory WHERE node_id = ?", nodeId)
                return@transactionResult 0
            }

            val changed = clients.sumOf { client ->
                recordTrafficDelta(tx, nodeId, client)
                tx.execute(
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
            tx.execute(
                "DELETE FROM node_client_inventory WHERE node_id = ? AND xray_email <> ALL(?::text[])",
                nodeId,
                toPgTextArray(emails)
            )
            changed
        }
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

    fun listByEmail(email: String): List<NodeClientInventoryEntity> {
        return dsl.fetch(
            """
            SELECT id, node_id, inbound_id, inbound_remark, inbound_tag, xray_email,
                   vless_uuid, flow, enabled, total_bytes, up_bytes, down_bytes,
                   expiry_time, limit_ip, sub_id, telegram_id, last_synced_at
            FROM node_client_inventory
            WHERE xray_email = ?
            ORDER BY last_synced_at DESC
            """.trimIndent(),
            email
        ).map(::map)
    }

    fun deleteByNodeAndEmail(nodeId: UUID, email: String): Boolean {
        return dsl.execute(
            "DELETE FROM node_client_inventory WHERE node_id = ? AND xray_email = ?",
            nodeId,
            email
        ) > 0
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

    private fun recordTrafficDelta(tx: DSLContext, nodeId: UUID, client: XrayNodeClient) {
        val rec = tx.fetchOne(
            """
            SELECT c.id AS client_id, nci.up_bytes, nci.down_bytes, nci.last_synced_at
            FROM clients c
            LEFT JOIN node_client_inventory nci
              ON nci.node_id = c.node_id AND nci.xray_email = c.xray_email
            WHERE c.node_id = ? AND c.xray_email = ?
            """.trimIndent(),
            nodeId,
            client.email
        ) ?: return

        val clientId = rec.get("client_id", UUID::class.java) ?: return
        val previousUp = rec.get("up_bytes", Long::class.java)
        val previousDown = rec.get("down_bytes", Long::class.java)
        val deltaUp = deltaBytes(previousUp, client.upBytes)
        val deltaDown = deltaBytes(previousDown, client.downBytes)
        if (deltaUp <= 0L && deltaDown <= 0L) return

        val now = Instant.now()
        val previousSync = rec.get("last_synced_at", OffsetDateTime::class.java)?.toInstant()
        val periodStart = previousSync
            ?.takeIf { it.isBefore(now) }
            ?: now.minusSeconds(1)

        tx.execute(
            """
            INSERT INTO traffic_stats (
                client_id, node_id, period_start, period_end,
                uplink_bytes, downlink_bytes, source
            )
            VALUES (?, ?, ?::timestamptz, ?::timestamptz, ?, ?, 'XRAY_SYNC')
            ON CONFLICT (client_id, period_start, period_end) DO UPDATE SET
                uplink_bytes = traffic_stats.uplink_bytes + EXCLUDED.uplink_bytes,
                downlink_bytes = traffic_stats.downlink_bytes + EXCLUDED.downlink_bytes
            """.trimIndent(),
            clientId,
            nodeId,
            OffsetDateTime.ofInstant(periodStart, java.time.ZoneOffset.UTC),
            OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC),
            deltaUp,
            deltaDown
        )
    }

    private fun deltaBytes(previous: Long?, current: Long): Long {
        if (current <= 0L) return 0L
        return if (previous == null || current < previous) current else current - previous
    }

    private fun toPgTextArray(values: List<String>): String {
        return values.joinToString(prefix = "{", postfix = "}") { v ->
            val escaped = v.replace("\\", "\\\\").replace("\"", "\\\"")
            "\"$escaped\""
        }
    }
}
