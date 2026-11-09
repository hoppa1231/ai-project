package com.example.modules.vpn

import com.example.config.AppContext
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.*

fun Application.startVpnRevocationWorker(context: AppContext) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    monitor.subscribe(ApplicationStopping) { scope.cancel() }
    scope.launch {
        while (isActive) {
            try {
                for (config in context.vpn.pendingRevocations()) {
                    val nodes = context.vpn.listConfigHops(config.id).map { it.nodeId }.ifEmpty { listOf(config.nodeId) }
                    if (nodes.any { context.nodes.findById(it)?.health != "HEALTHY" }) continue
                    try { revokeIssuedConfig(context, config, "deferred_revoke") }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Keep the durable pending marker for the next attempt. */ }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Database outages must not terminate reconciliation. */ }
            delay(20_000)
        }
    }
}
