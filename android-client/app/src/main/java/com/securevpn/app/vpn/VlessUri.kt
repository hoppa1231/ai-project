package com.securevpn.app.vpn

import android.net.Uri
import java.net.URLDecoder

data class VlessProfile(
    val uuid: String,
    val host: String,
    val port: Int,
    val remark: String,
    val security: String?,
    val sni: String?,
    val fingerprint: String?,
    val publicKey: String?,
    val shortId: String?,
    val spiderX: String?,
    val transportType: String?,
    val encryption: String?,
    val flow: String?
)

fun parseVlessUri(raw: String): VlessProfile {
    val uri = Uri.parse(raw)
    require(uri.scheme == "vless") { "Only vless:// links are supported" }

    val uuid = uri.userInfo?.takeIf { it.isNotBlank() }
        ?: error("VLESS UUID is missing")
    val host = uri.host?.takeIf { it.isNotBlank() }
        ?: error("VLESS host is missing")
    val port = if (uri.port > 0) uri.port else 443
    val remark = uri.fragment?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }.orEmpty()

    return VlessProfile(
        uuid = uuid,
        host = host,
        port = port,
        remark = remark,
        security = uri.getQueryParameter("security"),
        sni = uri.getQueryParameter("sni"),
        fingerprint = uri.getQueryParameter("fp"),
        publicKey = uri.getQueryParameter("pbk"),
        shortId = uri.getQueryParameter("sid"),
        spiderX = uri.getQueryParameter("spx"),
        transportType = uri.getQueryParameter("type"),
        encryption = uri.getQueryParameter("encryption"),
        flow = uri.getQueryParameter("flow")
    )
}
