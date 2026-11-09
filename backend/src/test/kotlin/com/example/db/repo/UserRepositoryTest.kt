package com.example.db.repo

import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockDataProvider
import org.jooq.tools.jdbc.MockResult
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserRepositoryTest {
    private fun repository(telegramId: Long?): UserRepository {
        val provider = MockDataProvider {
            val dsl = DSL.using(SQLDialect.POSTGRES)
            val field = DSL.field("telegram_id", SQLDataType.BIGINT)
            val record = dsl.newRecord(field)
            record.set(field, telegramId)
            val result = dsl.newResult(field)
            result.add(record)
            arrayOf(MockResult(1, result))
        }
        return UserRepository(DSL.using(MockConnection(provider), SQLDialect.POSTGRES))
    }

    @Test fun guestHasNoTelegramIdInsteadOfPrimitiveZero() {
        assertNull(repository(null).getTelegramId(UUID.randomUUID()))
    }

    @Test fun linkedTelegramIdIsPreserved() {
        assertEquals(123456L, repository(123456L).getTelegramId(UUID.randomUUID()))
    }
    @Test fun telegramProfileIncludesIdentityAndAllowsMissingNames() {
        for (linked in listOf(false, true)) {
            val provider = MockDataProvider {
                val dsl = DSL.using(SQLDialect.POSTGRES)
                val id = DSL.field("telegram_id", SQLDataType.BIGINT)
                val username = DSL.field("telegram_username", SQLDataType.VARCHAR)
                val first = DSL.field("telegram_first_name", SQLDataType.VARCHAR)
                val last = DSL.field("telegram_last_name", SQLDataType.VARCHAR)
                val record = dsl.newRecord(id, username, first, last)
                if (linked) {
                    record.set(id, 123456L)
                    record.set(username, "tester")
                    record.set(first, "Test")
                }
                val result = dsl.newResult(id, username, first, last)
                result.add(record)
                arrayOf(MockResult(1, result))
            }
            val repo = UserRepository(DSL.using(MockConnection(provider), SQLDialect.POSTGRES))
            assertEquals(
                if (linked) Triple(123456L, "tester", "Test") else Triple(null, null, null),
                repo.telegramProfile(UUID.randomUUID())
            )
        }
    }
}
