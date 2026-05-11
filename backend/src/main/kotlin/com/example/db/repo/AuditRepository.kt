package com.example.db.repo

import io.ktor.server.application.ApplicationCall
import org.jooq.DSLContext
import java.util.UUID

class AuditRepository(private val dsl: DSLContext) {
    fun log(
        action: String,
        success: Boolean,
        actorUserId: UUID?,
        actorDeviceId: UUID?,
        targetType: String,
        targetId: UUID?,
        detailsJson: String,
        call: ApplicationCall?
    ) {
        val requestId = call?.request?.headers?.get("X-Request-Id")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val ip = call?.request?.local?.remoteHost
        val ua = call?.request?.headers?.get("User-Agent")

        dsl.execute(
            """
            INSERT INTO audit_log (
                actor_user_id, actor_device_id, action,
                target_type, target_id, request_id,
                ip, user_agent, success, details
            )
            VALUES (?, ?, ?, ?, ?, ?, ?::inet, ?, ?, ?::jsonb)
            """.trimIndent(),
            actorUserId,
            actorDeviceId,
            action,
            targetType,
            targetId,
            requestId,
            ip,
            ua,
            success,
            detailsJson
        )
    }
}
