package com.example.db

import com.example.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import javax.sql.DataSource

class DatabaseBundle(
    val dataSource: DataSource,
    val dsl: DSLContext
)

object DatabaseFactory {
    fun create(config: AppConfig): DatabaseBundle {
        val hikari = HikariConfig().apply {
            jdbcUrl = config.dbUrl
            username = config.dbUser
            password = config.dbPassword
            maximumPoolSize = 10
            minimumIdle = 1
            isAutoCommit = true
        }

        val dataSource = HikariDataSource(hikari)

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate()

        val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        return DatabaseBundle(dataSource, dsl)
    }
}
