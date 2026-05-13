package com.example.db.repo

import com.example.db.UserEntity
import org.jooq.DSLContext
import java.util.UUID

class UserRepository(private val dsl: DSLContext) {
    fun createRegistered(email: String, passwordHash: String): UserEntity {
        val rec = dsl.fetchOne(
            """
            INSERT INTO users (email, password_hash, account_type, registered_at)
            VALUES (?, ?, 'REGISTERED', now())
            RETURNING id, email, password_hash, role, status, account_type
            """.trimIndent(),
            email,
            passwordHash
        ) ?: error("failed to insert user")

        return mapUser(rec)
    }

    fun createGuest(email: String, passwordHash: String): UserEntity {
        val rec = dsl.fetchOne(
            """
            INSERT INTO users (email, password_hash, account_type)
            VALUES (?, ?, 'GUEST')
            RETURNING id, email, password_hash, role, status, account_type
            """.trimIndent(),
            email,
            passwordHash
        ) ?: error("failed to insert guest user")

        return mapUser(rec)
    }

    fun createTelegram(
        telegramId: Long,
        username: String?,
        firstName: String?,
        lastName: String?,
        photoUrl: String?,
        passwordHash: String
    ): UserEntity {
        val email = "tg_$telegramId@telegram.local"
        val rec = dsl.fetchOne(
            """
            INSERT INTO users (
                email, password_hash, account_type, registered_at,
                telegram_id, telegram_username, telegram_first_name, telegram_last_name, telegram_photo_url
            )
            VALUES (?, ?, 'REGISTERED', now(), ?, ?, ?, ?, ?)
            ON CONFLICT (telegram_id) DO UPDATE SET
                telegram_username = EXCLUDED.telegram_username,
                telegram_first_name = EXCLUDED.telegram_first_name,
                telegram_last_name = EXCLUDED.telegram_last_name,
                telegram_photo_url = EXCLUDED.telegram_photo_url,
                last_login_at = now(),
                updated_at = now()
            RETURNING id, email, password_hash, role, status, account_type
            """.trimIndent(),
            email,
            passwordHash,
            telegramId,
            username,
            firstName,
            lastName,
            photoUrl
        ) ?: error("failed to upsert telegram user")

        return mapUser(rec)
    }

    fun upgradeGuest(userId: UUID, email: String, passwordHash: String): UserEntity? {
        val rec = dsl.fetchOne(
            """
            UPDATE users
            SET email = ?,
                password_hash = ?,
                account_type = 'REGISTERED',
                registered_at = now(),
                updated_at = now()
            WHERE id = ? AND account_type = 'GUEST'
            RETURNING id, email, password_hash, role, status, account_type
            """.trimIndent(),
            email,
            passwordHash,
            userId
        ) ?: return null

        return mapUser(rec)
    }

    fun findByEmail(email: String): UserEntity? {
        val rec = dsl.fetchOne(
            """
            SELECT id, email, password_hash, role, status, account_type
            FROM users
            WHERE email = ?
            """.trimIndent(),
            email
        ) ?: return null

        return mapUser(rec)
    }

    fun findById(id: UUID): UserEntity? {
        val rec = dsl.fetchOne(
            """
            SELECT id, email, password_hash, role, status, account_type
            FROM users
            WHERE id = ?
            """.trimIndent(),
            id
        ) ?: return null

        return mapUser(rec)
    }

    fun findByTelegramId(telegramId: Long): UserEntity? {
        val rec = dsl.fetchOne(
            """
            SELECT id, email, password_hash, role, status, account_type
            FROM users
            WHERE telegram_id = ?
            """.trimIndent(),
            telegramId
        ) ?: return null

        return mapUser(rec)
    }

    fun getTelegramId(userId: UUID): Long? {
        return dsl.fetchOne(
            "SELECT telegram_id FROM users WHERE id = ?",
            userId
        )?.get("telegram_id", Long::class.java)
    }

    fun touchLastLogin(id: UUID) {
        dsl.execute("UPDATE users SET last_login_at = now() WHERE id = ?", id)
    }

    fun setRole(id: UUID, role: String) {
        dsl.execute("UPDATE users SET role = ?, updated_at = now() WHERE id = ?", role, id)
    }

    private fun mapUser(rec: org.jooq.Record): UserEntity {
        return UserEntity(
            id = rec.get("id", UUID::class.java)!!,
            email = rec.get("email", String::class.java)!!,
            passwordHash = rec.get("password_hash", String::class.java)!!,
            role = rec.get("role", String::class.java)!!,
            status = rec.get("status", String::class.java)!!,
            accountType = rec.get("account_type", String::class.java)!!
        )
    }
}
