package com.example.db.repo

import com.example.db.QuotaSnapshot
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class QuotaRepository(private val dsl: DSLContext) {
    fun current(userId: UUID, freeGbPerMonth: Long): QuotaSnapshot {
        val cycleStart = currentCycleStart()
        val cycleEnd = cycleStart.plusMonths(1)
        val freeBytes = gbToBytes(freeGbPerMonth)

        ensureWallet(dsl, userId, cycleStart, freeBytes)

        val wallet = dsl.fetchOne(
            """
            SELECT free_bytes, purchased_bytes
            FROM quota_wallets
            WHERE user_id = ? AND cycle_start = ?
            """.trimIndent(),
            userId,
            cycleStart
        ) ?: error("quota wallet missing")

        val free = (wallet.get("free_bytes", Number::class.java) ?: 0).toLong()
        val purchased = (wallet.get("purchased_bytes", Number::class.java) ?: 0).toLong()

        val usage = dsl.fetchOne(
            """
            SELECT COALESCE(SUM(ts.uplink_bytes + ts.downlink_bytes), 0) AS used_bytes
            FROM traffic_stats ts
            JOIN clients c ON c.id = ts.client_id
            WHERE c.user_id = ?
              AND ts.period_start >= ?::timestamptz
              AND ts.period_start < ?::timestamptz
            """.trimIndent(),
            userId,
            cycleStart.atStartOfDay().atOffset(ZoneOffset.UTC),
            cycleEnd.atStartOfDay().atOffset(ZoneOffset.UTC)
        )
        val used = (usage?.get("used_bytes", Number::class.java) ?: 0).toLong()

        val remaining = (free + purchased - used).coerceAtLeast(0)

        return QuotaSnapshot(
            cycleStart = cycleStart,
            cycleEnd = cycleEnd,
            freeBytes = free,
            purchasedBytes = purchased,
            usedBytes = used,
            remainingBytes = remaining
        )
    }

    fun grantPurchased(
        userId: UUID,
        gb: Long,
        source: String,
        externalRef: String?,
        freeGbPerMonth: Long
    ): QuotaSnapshot {
        require(gb > 0) { "gb must be positive" }

        val cycleStart = currentCycleStart()
        val bytes = gbToBytes(gb)
        val freeBytes = gbToBytes(freeGbPerMonth)

        dsl.transaction { cfg ->
            val tx = DSL.using(cfg)
            ensureWallet(tx, userId, cycleStart, freeBytes)
            tx.execute(
                """
                UPDATE quota_wallets
                SET purchased_bytes = purchased_bytes + ?
                WHERE user_id = ? AND cycle_start = ?
                """.trimIndent(),
                bytes,
                userId,
                cycleStart
            )
            tx.execute(
                """
                INSERT INTO quota_topups (user_id, cycle_start, bytes, source, external_ref)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                userId,
                cycleStart,
                bytes,
                source,
                externalRef
            )
        }

        return current(userId, freeGbPerMonth)
    }

    private fun ensureWallet(dsl: DSLContext, userId: UUID, cycleStart: LocalDate, freeBytes: Long) {
        dsl.execute(
            """
            INSERT INTO quota_wallets (user_id, cycle_start, free_bytes, purchased_bytes)
            VALUES (?, ?, ?, 0)
            ON CONFLICT (user_id, cycle_start) DO NOTHING
            """.trimIndent(),
            userId,
            cycleStart,
            freeBytes
        )
    }

    private fun currentCycleStart(): LocalDate {
        val now = LocalDate.now(ZoneOffset.UTC)
        return now.withDayOfMonth(1)
    }

    private fun gbToBytes(gb: Long): Long = gb * 1024L * 1024L * 1024L
}
