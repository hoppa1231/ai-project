package com.example.db.repo

import com.example.db.AppNotificationEntity
import org.jooq.DSLContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class AppNotificationRepository(private val dsl: DSLContext) {
    fun create(
        title: String,
        body: String,
        severity: String,
        targetUserId: UUID?,
        targetDeviceId: UUID?,
        expiresAt: Instant?,
        createdByUserId: UUID?
    ): AppNotificationEntity {
        val rec = dsl.fetchOne(
            """
            INSERT INTO app_notifications (
                title, body, severity, target_user_id, target_device_id,
                expires_at, created_by_user_id
            )
            VALUES (?, ?, ?, ?, ?, ?::timestamptz, ?)
            RETURNING id, target_user_id, target_device_id, title, body, severity,
                      starts_at, expires_at, created_at
            """.trimIndent(),
            title,
            body,
            severity,
            targetUserId,
            targetDeviceId,
            expiresAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
            createdByUserId
        ) ?: error("failed to create notification")

        return map(rec)
    }

    fun listActiveFor(userId: UUID, deviceId: UUID?, includeRead: Boolean = false): List<AppNotificationEntity> {
        val records = dsl.fetch(
            """
            SELECT n.id, n.target_user_id, n.target_device_id, n.title, n.body, n.severity,
                   n.starts_at, n.expires_at, n.created_at
            FROM app_notifications n
            WHERE n.active = true
              AND n.starts_at <= now()
              AND (n.expires_at IS NULL OR n.expires_at > now())
              AND (n.target_user_id IS NULL OR n.target_user_id = ?)
              AND (n.target_device_id IS NULL OR n.target_device_id = ?)
              AND (
                ? = true OR NOT EXISTS (
                  SELECT 1
                  FROM app_notification_reads r
                  WHERE r.notification_id = n.id
                    AND r.user_id = ?
                    AND r.device_id = ?
                )
              )
            ORDER BY n.created_at DESC
            LIMIT 20
            """.trimIndent(),
            userId,
            deviceId,
            includeRead,
            userId,
            deviceId
        )

        return records.map(::map)
    }

    fun markRead(notificationId: UUID, userId: UUID, deviceId: UUID) {
        dsl.execute(
            """
            INSERT INTO app_notification_reads (notification_id, user_id, device_id)
            SELECT id, ?, ?
            FROM app_notifications
            WHERE id = ?
              AND active = true
              AND (target_user_id IS NULL OR target_user_id = ?)
              AND (target_device_id IS NULL OR target_device_id = ?)
            ON CONFLICT (notification_id, user_id, device_id) DO NOTHING
            """.trimIndent(),
            userId,
            deviceId,
            notificationId,
            userId,
            deviceId
        )
    }

    private fun map(rec: org.jooq.Record): AppNotificationEntity {
        return AppNotificationEntity(
            id = rec.get("id", UUID::class.java)!!,
            targetUserId = rec.get("target_user_id", UUID::class.java),
            targetDeviceId = rec.get("target_device_id", UUID::class.java),
            title = rec.get("title", String::class.java)!!,
            body = rec.get("body", String::class.java)!!,
            severity = rec.get("severity", String::class.java)!!,
            startsAt = rec.get("starts_at", OffsetDateTime::class.java)!!.toInstant(),
            expiresAt = rec.get("expires_at", OffsetDateTime::class.java)?.toInstant(),
            createdAt = rec.get("created_at", OffsetDateTime::class.java)!!.toInstant()
        )
    }
}
