package com.example.modules.health

import com.example.config.AppContext
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

fun Application.startNodeHealthWorker(context: AppContext) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    monitor.subscribe(ApplicationStopping) {
        scope.cancel()
    }

    scope.launch {
        while (isActive) {
            val nodes = context.nodes.listAll()
            nodes.forEach { node ->
                try {
                    val latency = context.xray.ping(node)
                    context.nodes.updateHealth(node.id, "HEALTHY", null, latency)
                } catch (e: Exception) {
                    context.nodes.updateHealth(node.id, "UNHEALTHY", e.message ?: "ping failed", null)
                }
            }
            delay(20_000)
        }
    }
}
