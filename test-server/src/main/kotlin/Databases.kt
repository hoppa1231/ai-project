package com.example

import io.ktor.server.application.*

import org.jetbrains.exposed.v1.core.*              // Зависимости для Exposed'а
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.mindrot.jbcrypt.BCrypt

private fun hashPassword(password: String): String = BCrypt.hashpw(password, BCrypt.gensalt())

fun Application.configureDatabases() {
    transaction {
        addLogger(StdOutSqlLogger)  // Создаем логгирование
        SchemaUtils.drop(
            Traffic_use_tables,
            Issued_configs_tables,
            Policies_tables,
            Node_clients_tables,
            Users_tables,
            VPN_nodes_tables
        )
        SchemaUtils.create(
            VPN_nodes_tables,  // создаём сначала таблицы, на которые есть ссылки
            Users_tables,
            Node_clients_tables,
            Policies_tables,
            Issued_configs_tables,
            Traffic_use_tables
        )
            
        // Метод для создания новой записи - new()
        val user1 = Users_table.new {
            login       = "glack"
            password    = hashPassword("YashaNyasha2004")
            status      = StatusUser.of("ONLINE")
        }

        val user2 = Users_table.new {
            login       = "yasha"
            password    = hashPassword("YashaLuchshiy1984")
            status      = StatusUser.of("FROZED")
        }

        val chikibryakiya = VPN_nodes_table.new {
            region  = "ru"
            address = "195.231.203.10"
            status  = StatusNode.of("ACTIVE")
            online  = 12323
        }

        // Обращение такое же, как без DAO
        println("id ${user1.id} ei ${user2.id}, name ${user1.login} ei ${user2.login}\n")
        println("status of user1: ${user1.status}, his servers: ${user1.server}\n")

        user1.server = chikibryakiya.id

        val chikibryakiya_user1_traf = Node_clients_table.new {
            server = chikibryakiya.id
            client = "14881337"
        }

        val route_to_germaniya = Policies_table.new {
            filename    = "ZAHVATI_GERMANIYU"
            user        = user2.id
        }

        val config_g = Issued_configs_table.new {
            version     = 12
            name        = "svo"
            server      = chikibryakiya.id
        }

        val user2traffic = Traffic_use_table.new {
            user        = user2.id
            limit       = null
        }

        println("\n\nChikibryakiya ID: ${chikibryakiya.id}, region: ${chikibryakiya.region}, status: ${chikibryakiya.status} now user1 server is: ${user1.server}\n")
        println("Known traffic of server with id ${chikibryakiya_user1_traf.server}")
        println("Route: ${route_to_germaniya.user}\n")
        println("Created config on server with id: ${config_g.server}, version is ${config_g.version}\n")
        println("Limit for user2 with ID ${user2traffic.user} is ${user2traffic.limit}")
        /*
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