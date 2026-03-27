package com.example

import io.ktor.server.application.*

import org.jetbrains.exposed.v1.core.*              // Зависимости для Exposed'а
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

fun Application.configureDatabases() {
    transaction {
        addLogger(StdOutSqlLogger)  // Создаем логгирование
        SchemaUtils.create(Users_tables)   // Создание конкретной таблицы
        SchemaUtils.create(VPN_nodes_tables)
        SchemaUtils.create(Node_clients_tables)
        SchemaUtils.create(Policies_tables)
        SchemaUtils.create(Issued_configs_tables)
        SchemaUtils.create(Traffic_use_tables)
        /*
        // Метод для создания новой записи - new()
        val task1 = FirstRel.new {
            title = "Raki"
            description = "S jenei snyali"
        }

        val task2 = FirstRel.new {
            title = "Bipki"
            description = "Ososesh - skaju"
            amount = 20
        }

        // Обращение такое же, как без DAO
        println("Sozdal govno s id ${task1.id} ei ${task2.id}")
        // Методом find() выполняем запрос, который ищет все задачи с полем isCompleted = true
        // преобразуя их в список.
        val completed = FirstRel.find { FirstRels.amount eq 20 }.toList()
        // Подсчитываем число элементов списка.
        println("Bipki: ${completed.count()}")
        // Обновляем запись.
        task1.title = "Poesh govna"
        task1.amount = 1
        println("Obnovil proveryaii: $task1")

        // Удаляем запись.
        task2.delete() 
        println("Ostavshiesya hueta: ${FirstRel.all().toList()}")
        task1.delete()
        */
    }
}
/* 
Методы сноса отношений, если известен ID и они созданы в другой сессии
val task1 = FirstRel.findById(1)
println(task1)
transaction {
    UsersTable.deleteWhere { UsersTable.id eq entityId } // Delete directly with a condition
}
*/