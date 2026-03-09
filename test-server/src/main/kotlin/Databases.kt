package com.example

import io.ktor.server.application.*

import org.jetbrains.exposed.v1.core.*              // Зависимости для Exposed'а
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

import Tasks // Таблицы БД 

fun Application.configureDatabases() {
    // jdbc - указание на JDBC-соединение
    // h2 - база данных H2
    // mem - БД находится в ОП, т.е. данные будут потеряны при остановке сервера
    // test - имя БД
    // org.h2.Driver - указание на драйвер для H2 JDBC, которые используется для
    // установки соединения.
    Database.connect("jdbc:h2:mem:test", driver = "org.h2.Driver")
    transaction {
        addLogger(StdOutSqlLogger)
        SchemaUtils.create(Tasks)   // Создание таблицы задач
                                    // Содержит вспомогательные методы для создания,
                                    // изменения и удаления объектов БД.

        val taskId = Tasks.insert {  // Метод класса Table для добавления новых записей.
            // Здесь добавили заголовок и описание, запросив id (автоинкрементный)
            it[title] = "Sololeveling"
            it[description] = "luchee anime"
        } get Tasks.id

        val secondTaskId = Tasks.insert {
            // Здесь также указали переменную, которая в прошлой записи автматически false
            it[title] = "Prochitat' Tomozaki"
            it[description] = "luchaya romkom ranobe!"
            it[isCompleted] = true
        } get Tasks.id

        // Вывод присвоенных id
        println("Sozdan' novie zadachi s id $taskId i $secondTaskId.") 

        // Используем select для подсчета записей в отношении Tasks, группируя их по полю isCompleted
        Tasks.select(Tasks.id.count(), Tasks.isCompleted).groupBy(Tasks.isCompleted).forEach {
            println("${it[Tasks.isCompleted]}: ${it[Tasks.id.count()]} ")
        }

        println("Ostavshiesya zadachi: ${Tasks.selectAll().toList()}")
    }
}