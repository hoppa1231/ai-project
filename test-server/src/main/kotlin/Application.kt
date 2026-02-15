package com.example

import io.ktor.server.application.*
import io.ktor.server.routing.RoutingRoot
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*

suspend fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
    val client = HttpClient()

    client.close()
}

fun Application.module() {
    configureHTTP()
    configureRouting()
}
