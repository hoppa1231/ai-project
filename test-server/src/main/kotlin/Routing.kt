package com.example

import com.asyncapi.kotlinasyncapi.context.service.AsyncApiExtension
import com.asyncapi.kotlinasyncapi.ktor.AsyncApiPlugin
import io.ktor.http.ContentType
import io.ktor.openapi.*
import io.ktor.server.application.*
import io.ktor.server.plugins.openapi.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("localhost:8080/vpn/nodes", contentType = ContentType.Text.Any)
        }
        get("/vpn/nodes") { // Список доступных серверов
            val file = File("files/sth.json")
            call.respondFile(file)
        }
        get("/policy/current") {

        }
        post("/auth/register"){

        }
        post("/auth/login"){

        }
        post("/devices/bind"){

        }
        post("/vpn/config"){

        }
        post("/telemetry"){

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