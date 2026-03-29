package com.example

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.request.receive
import io.ktor.server.routing.*
import io.ktor.http.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlinx.serialization.Serializable

@Serializable
data class UserResponse(
    val id: Int,
    val login: String,
    val status: String = "OFFLINE"
)

@Serializable
data class UserRequest(
    val login: String,
    val password: String,
    val status: String 
)

fun Application.configureRouting() {
    routing {
        // Operations with DB [create]
        
        


        get("/vpn/nodes") { // Список доступных серверов
            call.respondText("доступные сервера: МАЙНКРАФТ")
        }
        get("/policy/current") {
            call.respondText("политика: ДЛЯ ДОЛБАЕБОВ")
        }
        post("/auth/register"){
            call.respondText("зарегестрируйся: В МАКСЕ")
        }
        post("/auth/login"){
            call.respondText("залогинься: В ГОСУСЛУГАХ")
        }
        post("/devices/bind"){
            call.respondText("мобилки компутеры аппараты жизнеобеспечения")
        }
        post("/vpn/config"){
            call.respondText("конфигурация: ДЛЯ ДЕБИЛИЗАЦИИ")
        }
        post("/telemetry"){
            call.respondText("телеметрия: ИБО ТОК ТЕЛЕК ОСТАЛСЯ")
        }
        // ------ //
        // CRUD
        get("/users/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid id")

            val user = transaction {
                Users_table.findById(id)
            } ?: return@get call.respond(HttpStatusCode.NotFound, "User not found")

            call.respond(UserResponse(
                id = user.id.value,
                login = user.login,
                status = user.status.value
            ))
        }

        post("/users") {
            val body = call.receive<UserRequest>()

            val status = StatusUser.safeOf(body.status)
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid status: ${body.status}")

            val newUser = transaction {
                Users_table.new {
                    login    = body.login
                    password = body.password
                    this.status = status
                }
            }

            call.respond(HttpStatusCode.Created, mapOf("id" to newUser.id.value))
        }

        put("/users/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid id")
            
            val body = call.receive<UserRequest>()

            val status = StatusUser.safeOf(body.status)
            ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid status: ${body.status}")

            val updated = transaction {
                val user = Users_table.findById(id) ?: return@transaction null
                user.login = body.login
                user.password = body.password
                user.status = status
                user
            } ?: return@put call.respond(HttpStatusCode.NotFound, "User not found")

            call.respond(HttpStatusCode.OK, UserResponse(
                id = updated.id.value,
                login = updated.login,
                status = updated.status.value
            ))
        }

        delete("/users/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid id")

            val deleted = transaction {
                val user = Users_table.findById(id) ?: return@transaction false
                user.delete()
                true
            }

            if (deleted) call.respondText("User deleted", ContentType.Text.Plain, HttpStatusCode.OK)
            else call.respond(HttpStatusCode.NotFound, "User not found")
        }
        
    }
}

//        "====================================================================================\n" +
//        "=====00=======0000000==0000000000==00==00000000========0000000==00=======00=========\n" +
//        "=====00=======00===========00======00==00==============00===00==00=======00=========\n" +
//        "=====00=======0000000======00======00==00000000========0000000==00=======00=========\n" +
//        "=====00=======00===========00================00========00===00==00=======00=========\n" +
//        "=====0000000==0000000======00==========00000000========00===00==0000000==0000000====\n" +
//        "====================================================================================\n" +
//        "==00=======00000000==00===00==0000000========00=======0000000==00000000===000===00==\n" +
//        "==00=======00====00==00===00==00=============00=======00===00=====00======0000==00==\n" +
//        "==00=======00====00==00===00==0000000========00=======0000000=====00======00=00=00==\n" +
//        "==00=======00====00===00=00===00=============00=======00===00=====00======00==0000==\n" +
//        "==0000000==00000000====000====0000000========0000000==00===00==00000000===00===000==\n" +
//        "===================================================================================="