package com.example.db.repo

import com.example.db.RefreshTokenEntity
import org.jooq.DSLContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class RefreshTokenRepository(private val dsl: DSLContext) {
    fun create(
        userId: UUID,
        deviceId: UUID,
        jti: UUID,
        tokenHash: String,
        expiresAt: Instant,
        ip: String?,
        userAgent: String?
    ) {
        dsl.execute(
            """
            INSERT INTO refresh_tokens (
                user_id, device_id, jti, token_hash, expires_at, ip, user_agent
            )
            VALUES (?, ?, ?, ?, ?::timestamptz, ?::inet, ?)
            """.trimIndent(),
            userId,
            deviceId,
            jti,
            tokenHash,
            OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC),
            ip,
            userAgent
        )
    }

    fun findActive(jti: UUID, tokenHash: String): RefreshTokenEntity? {
        val rec = dsl.fetchOne(
            """
            SELECT jti, user_id, device_id, token_hash, expires_at, revoked_at
            FROM refresh_tokens
            WHERE jti = ? AND token_hash = ?
            """.trimIndent(),
            jti,
            tokenHash
        ) ?: return null

        return RefreshTokenEntity(
            jti = rec.get("jti", UUID::class.java)!!,
            userId = rec.get("user_id", UUID::class.java)!!,
            deviceId = rec.get("device_id", UUID::class.java)!!,
            tokenHash = rec.get("token_hash", String::class.java)!!,
            expiresAt = rec.get("expires_at", OffsetDateTime::class.java)!!.toInstant(),
            revokedAt = rec.get("revoked_at", OffsetDateTime::class.java)?.toInstant()
        )
    }

    fun revoke(jti: UUID, replacedByJti: UUID?) {
        dsl.execute(
            """
            UPDATE refresh_tokens
            SET revoked_at = now(), replaced_by_jti = ?
            WHERE jti = ? AND revoked_at IS NULL
            """.trimIndent(),
            replacedByJti,
            jti
        )
    }
}
