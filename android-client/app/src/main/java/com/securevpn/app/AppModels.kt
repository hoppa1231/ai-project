package com.securevpn.app

import com.securevpn.app.data.VpnNode
import com.securevpn.app.data.TelegramLinkedConfig
import java.util.UUID

enum class AppScreen {
    Home,
    Servers,
    Settings,
    Routing,
    Speed
}

enum class LinkState {
    Off,
    Connecting,
    Paused,
    On
}

data class VpnRuntimeState(
    val linkState: LinkState,
    val trafficText: String
)

data class ServerNode(
    val id: String,
    val city: String,
    val node: String,
    val region: String,
    val ping: Int,
    val load: Int,
    val recommended: Boolean = false,
    val vlessUri: String? = null,
    val usedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val available: Boolean = true
)

val ServerNodes = listOf(
    ServerNode("msk-7", "МОСКВА", "ВДНХ-7", "msk-7", 12, 38, recommended = true),
    ServerNode("spb-3", "ЛЕНИНГРАДЪ", "ВЫБОРГСКАЯ-3", "spb-3", 22, 51),
    ServerNode("min-2", "МИНСКЪ", "СЛУЦКАЯ-2", "min-2", 28, 47),
    ServerNode("tbi-1", "ТИФЛИСЪ", "ВОКЗАЛЬНЫЙ", "tbi-1", 58, 22),
    ServerNode("ala-1", "АЛМА-АТА", "ГОРНЫЙ-1", "ala-1", 86, 15),
    ServerNode("tas-1", "ТАШКЕНТЪ", "ВОСТОЧНЫЙ", "tas-1", 78, 33),
    ServerNode("wld-1", "ВЛАДИВОСТОКЪ", "ТИХООКЕАНСКIЙ", "wld-1", 142, 19),
    ServerNode("svr-1", "МУРМАНСКЪ", "СѢВЕРНЫЙ-1", "svr-1", 38, 11)
)

fun VpnNode.toServerNode(index: Int): ServerNode {
    val displayName = name.ifBlank { region.ifBlank { "УЗЕЛ-${index + 1}" } }
    val parts = displayName.split('-', '·', '–').map { it.trim() }.filter { it.isNotBlank() }
    val city = (parts.firstOrNull() ?: displayName).uppercase()
    val nodeLabel = (parts.drop(1).joinToString("-").ifBlank { region.ifBlank { id.take(8) } }).uppercase()
    val ping = when (health.uppercase()) {
        "HEALTHY" -> 12 + (index * 7) % 42
        "DEGRADED" -> 58 + (index * 11) % 48
        else -> 120 + (index * 13) % 70
    }
    return ServerNode(
        id = id,
        city = city,
        node = nodeLabel,
        region = region,
        ping = ping,
        load = load.coerceIn(0, 100),
        recommended = index == 0
    )
}

fun TelegramLinkedConfig.toServerNode(index: Int): ServerNode {
    val title = email
    val location = listOf(nodeName, region.uppercase(), if (enabled) "" else "ОТКЛЮЧЕН")
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .ifBlank { nodeId.take(8) }
    val usagePercent = if (totalBytes <= 0L) {
        0
    } else {
        (((upBytes + downBytes).toDouble() / totalBytes.toDouble()) * 100.0).toInt()
    }
    return ServerNode(
        id = "$nodeId:$email",
        city = title.uppercase(),
        node = location.uppercase(),
        region = region,
        ping = if (enabled) 18 + (index * 9) % 46 else 140,
        load = usagePercent.coerceIn(0, 100),
        recommended = index == 0,
        vlessUri = vlessUri,
        usedBytes = upBytes + downBytes,
        totalBytes = totalBytes,
        available = enabled
    )
}

fun String.isUuidString(): Boolean = runCatching { UUID.fromString(this) }.isSuccess
