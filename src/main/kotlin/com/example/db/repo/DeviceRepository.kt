package com.example.db.repo

import com.example.db.DeviceEntity
import org.jooq.DSLContext
import java.util.UUID

class DeviceRepository(private val dsl: DSLContext) {
    fun bindDevice(
        userId: UUID,
        fingerprintHash: String,
        deviceName: String,
        platform: String,
        appVersion: String?,
        publicKey: String?
    ): DeviceEntity {
        val rec = dsl.fetchOne(
            """
            INSERT INTO devices (user_id, device_fingerprint_hash, device_name, platform, app_version, public_key, status)
            VALUES (?, ?, ?, ?, ?, ?, 'ENABLED')
            ON CONFLICT (user_id, device_fingerprint_hash)
            DO UPDATE SET
                device_name = EXCLUDED.device_name,
                platform = EXCLUDED.platform,
                app_version = EXCLUDED.app_version,
                public_key = EXCLUDED.public_key,
                status = 'ENABLED',
                last_seen_at = now(),
                revoked_at = null
            RETURNING id, user_id, device_fingerprint_hash, device_name, platform, status
            """.trimIndent(),
            userId,
            fingerprintHash,
            deviceName,
            platform,
            appVersion,
            publicKey
        ) ?: error("failed to bind device")

        return DeviceEntity(
            id = rec.get("id", UUID::class.java)!!,
            userId = rec.get("user_id", UUID::class.java)!!,
            fingerprintHash = rec.get("device_fingerprint_hash", String::class.java)!!,
            deviceName = rec.get("device_name", String::class.java)!!,
            platform = rec.get("platform", String::class.java)!!,
            status = rec.get("status", String::class.java)!!
        )
    }

    fun findByIdForUser(deviceId: UUID, userId: UUID): DeviceEntity? {
        val rec = dsl.fetchOne(
            """
            SELECT id, user_id, device_fingerprint_hash, device_name, platform, status
            FROM devices
            WHERE id = ? AND user_id = ?
            """.trimIndent(),
            deviceId,
            userId
        ) ?: return null

        return DeviceEntity(
            id = rec.get("id", UUID::class.java)!!,
            userId = rec.get("user_id", UUID::class.java)!!,
            fingerprintHash = rec.get("device_fingerprint_hash", String::class.java)!!,
            deviceName = rec.get("device_name", String::class.java)!!,
            platform = rec.get("platform", String::class.java)!!,
            status = rec.get("status", String::class.java)!!
        )
    }

    fun touch(deviceId: UUID, userId: UUID) {
        dsl.execute(
            "UPDATE devices SET last_seen_at = now() WHERE id = ? AND user_id = ?",
            deviceId,
            userId
        )
    }
}
