package com.example.db.repo

import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockDataProvider
import org.jooq.tools.jdbc.MockResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeRepositoryTest {
    private fun query(region: String?, includeUnavailable: Boolean): String {
        var sql = ""
        val provider = MockDataProvider { context ->
            sql = context.sql()
            arrayOf(MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult()))
        }
        NodeRepository(DSL.using(MockConnection(provider), SQLDialect.POSTGRES))
            .listPublic(region, includeUnavailable)
        return sql
    }

    @Test fun catalogueIncludesUnhealthyButNeverInactiveOrEntryNodes() {
        for (region in listOf(null, "France")) {
            val sql = query(region, true)
            assertFalse(sql.contains("n.health = 'HEALTHY'"))
            assertTrue(sql.contains("n.status = 'ACTIVE'"))
            assertTrue(sql.contains("n.node_role IN ('EXIT', 'BOTH')"))
        }
    }

    @Test fun issuingConfigurationStillRequiresHealthyNodes() {
        for (region in listOf(null, "France")) {
            assertTrue(query(region, false).contains("n.health = 'HEALTHY'"))
        }
    }
}
