package com.example

import io.ktor.server.application.*

import org.jetbrains.exposed.v1.jdbc.Database
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    
    // Подключение к БД на PostgreSql
    val config = HikariConfig().apply {
        jdbcUrl = "jdbc:postgresql://localhost:5432/db"
        driverClassName = "org.postgresql.Driver"
        username = "admin"
        password = "admin123"

        maximumPoolSize = 40
        connectionTimeout = 20_000
    }   
    val dataSource = HikariDataSource(config)
    val postgresqldb = Database.connect(dataSource)
    
    // Подключение к временной БД для тестов
    // Database.connect("jdbc:h2:mem:test", driver = "org.h2.Driver")
    configureRouting()
    configureDatabases()
}
/*
// jdbc - указание на JDBC-соединение (Java DataBase Connectivity)
// postgresql - база данных Пострге
// //localhost:5432/db адрес + имя бд на локальном хосте из Docker
// driver - драйвер для установки соединения, обычный
// Настройки подключения к БД
val postgresqldb = Database.connect(
    "jdbc:postgresql://localhost:5432/db",
    driver = "org.postgresql.Driver",
    user = "admin",
    password = "admin123"        
)
*/