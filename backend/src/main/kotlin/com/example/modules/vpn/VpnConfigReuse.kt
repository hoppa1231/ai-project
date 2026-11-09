package com.example.modules.vpn

internal fun canReuseVpnConfig(force: Boolean, healthy: Boolean, unexpired: Boolean,
    currentNode: String, requestedNode: String?, currentMode: String, requestedMode: String): Boolean =
    !force && healthy && unexpired && (requestedNode == null || requestedNode == currentNode) && currentMode == requestedMode

internal fun retireVpnConfig(reachable: Boolean, revoke: () -> Unit, defer: () -> Unit) {
    if (!reachable) { defer(); return }
    try { revoke() }
    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { defer() }
}
