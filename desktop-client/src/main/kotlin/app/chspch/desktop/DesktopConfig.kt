package app.chspch.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

enum class TunnelMode { PROXY, TUN }
object DesktopConfig {
    const val PORT = 17890
    fun create(issued: JSONObject, policy: JSONObject, apiBaseUrl: String, mode: TunnelMode, processPaths: List<String> = emptyList()): JSONObject {
        val server = issued.optJSONObject("clientConfig") ?: error("Сервер не выдал sing-box clientConfig")
        require(server.optString("format") == "sing-box") { "Неизвестный формат конфигурации" }
        val source = server.optJSONArray("outbounds") ?: server.optJSONObject("outbound")?.let { JSONArray().put(it) }
            ?: error("Нет VPN outbound")
        require(source.length() > 0)
        val outbounds = JSONArray(source.toString())
        val vpnTag = server.optJSONObject("route")?.optString("final")?.takeIf { it.isNotBlank() }
            ?: outbounds.getJSONObject(outbounds.length() - 1).getString("tag")
        val bypass = JSONArray().put("1.1.1.1/32")
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.getJSONObject(i)
            require(outbound.optString("type") == "vless") { "Прототип поддерживает VLESS" }
            require(outbound.getString("tag") !in setOf("direct", "bootstrap", "remote"))
            outbound.put("domain_resolver", "bootstrap")
            val host = outbound.getString("server")
            if (host.matches(Regex("[0-9.]+"))) bypass.put("$host/32")
            else if (host.contains(':')) bypass.put("$host/128")
        }
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))
        val rules = JSONArray().put(JSONObject().put("action", "sniff"))
            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
            .put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
            .put(JSONObject().put("domain", JSONArray().put(URI(apiBaseUrl).host)).put("outbound", "direct"))
        rules.put(JSONObject().put("domain_suffix", JSONArray(listOf("ru", "su", "xn--p1ai"))).put("outbound", "direct"))
        if (processPaths.isNotEmpty()) rules.put(JSONObject().put("process_path", JSONArray(processPaths)).put("outbound", vpnTag))
        val dnsRules = JSONArray()
        val ruleSets = linkedMapOf<String, JSONObject>()
        val policyRules = policy.optJSONArray("routeRules") ?: JSONArray()
        (0 until policyRules.length()).map(policyRules::getJSONObject).filter { it.optBoolean("enabled", true) }
            .sortedBy { it.optInt("priority", 100) }.forEach { rule ->
                val values = rule.optJSONArray("values") ?: JSONArray()
                if (values.length() == 0 || rule.optString("matchType") == "APP_PACKAGE") return@forEach
                val type = rule.getString("matchType")
                val action = rule.optString("action", "VPN")
                val key = when(type) {
                    "DOMAIN" -> "domain"; "DOMAIN_SUFFIX" -> "domain_suffix"
                    "DOMAIN_KEYWORD" -> "domain_keyword"; "IP_CIDR" -> "ip_cidr"; "GEOIP" -> "rule_set"
                    else -> error("Неподдерживаемое правило: $type")
                }
                val matches = if (type == "GEOIP") JSONArray().also { array ->
                    for (i in 0 until values.length()) {
                        val code = values.getString(i).lowercase().removePrefix("geoip-")
                        require(code.matches(Regex("[a-z]{2}(-[a-z0-9]+)*")))
                        if (code == "ru-blocked" && action == "DIRECT") continue
                        val tag = "geoip-$code"
                        val url = if (code == "ru-blocked")
                            "https://raw.githubusercontent.com/runetfreedom/russia-v2ray-rules-dat/release/sing-box/rule-set-geoip/geoip-ru-blocked.srs"
                            else "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/$tag.srs"
                        ruleSets[tag] = JSONObject().put("type", "remote").put("tag", tag).put("format", "binary")
                            .put("url", url).put("download_detour", "direct")
                        array.put(tag)
                    }
                } else values
                if (matches.length() == 0) return@forEach
                val entry = JSONObject().put(key, matches)
                when(action) {
                    "BLOCK" -> entry.put("action", "reject")
                    "DIRECT", "VPN" -> entry.put("outbound", if (action == "DIRECT") "direct" else vpnTag)
                    else -> error("Неизвестное действие правила")
                }
                rules.put(entry)
                if (action == "VPN" && type in setOf("DOMAIN", "DOMAIN_SUFFIX", "DOMAIN_KEYWORD"))
                    dnsRules.put(JSONObject().put(key, matches).put("server", "remote"))
            }
        val inbounds = JSONArray().put(JSONObject().put("type", "mixed").put("tag", "local-proxy")
            .put("listen", "127.0.0.1").put("listen_port", PORT))
        if (mode == TunnelMode.TUN) inbounds.put(JSONObject().put("type", "tun").put("tag", "tun-in")
            .put("interface_name", "chspch0").put("address", JSONArray().put("172.29.255.1/30").put("fdfe:dcba:9876::1/126"))
            .put("mtu", 1400).put("dns_mode", "hijack")
            .put("auto_redirect", System.getProperty("os.name").startsWith("Linux")).put("auto_route", true).put("strict_route", true).put("route_exclude_address", bypass))
        return JSONObject().put("log", JSONObject().put("level", "info"))
            .put("inbounds", inbounds).put("outbounds", outbounds)
            .put("dns", JSONObject().put("servers", JSONArray()
                .put(JSONObject().put("type", "udp").put("tag", "bootstrap").put("server", "1.1.1.1"))
                .put(JSONObject().put("type", "tcp").put("tag", "remote").put("server", "1.1.1.1").put("detour", vpnTag)))
                .put("rules", dnsRules).put("final", "bootstrap").put("reverse_mapping", true))
            .put("route", JSONObject().put("auto_detect_interface", true).put("default_domain_resolver", "bootstrap")
                .put("rules", rules).put("rule_set", JSONArray(ruleSets.values.toList())).put("final", "direct"))
    }
}
