package com.securevpn.app.vpn

import com.securevpn.app.data.RouteRule
import com.securevpn.app.data.RoutingPolicy
import org.json.JSONArray
import org.json.JSONObject

object SingBoxConfigFactory {
    fun fromVlessUri(vlessUri: String, policy: RoutingPolicy = RoutingPolicy.default()): String {
        val profile = parseVlessUri(vlessUri)
        val outbound = JSONObject()
            .put("type", "vless")
            .put("tag", "proxy")
            .put("server", profile.host)
            .put("server_port", profile.port)
            .put("uuid", profile.uuid)
            .put("network", "tcp")
            .put("domain_resolver", BOOTSTRAP_DNS_TAG)

        profile.flow?.takeIf { it.isNotBlank() }?.let { outbound.put("flow", it) }

        if (profile.security == "reality") {
            require(!profile.publicKey.isNullOrBlank()) { "REALITY public key is missing" }
            outbound.put(
                "tls",
                JSONObject()
                    .put("enabled", true)
                    .put("server_name", profile.sni ?: profile.host)
                    .put(
                        "utls",
                        JSONObject()
                            .put("enabled", true)
                            .put("fingerprint", profile.fingerprint ?: "chrome")
                    )
                    .put(
                        "reality",
                        JSONObject()
                            .put("enabled", true)
                            .put("public_key", profile.publicKey)
                            .put("short_id", profile.shortId ?: "")
                    )
            )
        }

        val routeRules = JSONArray()
            .put(
                JSONObject()
                    .put("protocol", "dns")
                    .put("action", "hijack-dns")
            )
            .put(
                JSONObject()
                    .put("ip_is_private", true)
                    .put("outbound", "direct")
            )

        policy.routeRules
            .filter { it.enabled && it.values.isNotEmpty() }
            .sortedBy { it.priority }
            .mapNotNull(::routeRuleJson)
            .forEach(routeRules::put)

        val route = JSONObject()
            .put("auto_detect_interface", true)
            .put("default_domain_resolver", BOOTSTRAP_DNS_TAG)
            .put("rules", routeRules)
            .put("final", if (policy.defaultRoute == "DIRECT") "direct" else "proxy")

        if (profile.host.isIpv4Address()) {
            routeRules.put(
                JSONObject()
                    .put("ip_cidr", JSONArray().put("${profile.host}/32"))
                    .put("outbound", "direct")
            )
        }

        return JSONObject()
            .put("log", JSONObject().put("level", "info"))
            .put(
                "dns",
                JSONObject()
                    .put(
                        "servers",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("type", "udp")
                                    .put("tag", BOOTSTRAP_DNS_TAG)
                                    .put("server", "1.1.1.1")
                                    .put("server_port", 53)
                            )
                            .put(
                                JSONObject()
                                    .put("type", "tcp")
                                    .put("tag", REMOTE_DNS_TAG)
                                    .put("server", "1.1.1.1")
                                    .put("server_port", 53)
                                    .put("detour", "proxy")
                            )
                    )
                    .put("final", BOOTSTRAP_DNS_TAG)
                    .put("strategy", "ipv4_only")
                    .put("reverse_mapping", true)
            )
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "tun")
                        .put("tag", "tun-in")
                        .put("interface_name", "tun0")
                        .put("address", JSONArray().put("172.19.0.1/30"))
                        .put("mtu", 1400)
                        .put("auto_route", true)
                        .put("strict_route", false)
                        .put("sniff", true)
                        .put("sniff_override_destination", true)
                        .put("route_exclude_address", routeExcludeAddresses(profile))
                )
            )
            .put(
                "outbounds",
                JSONArray()
                    .put(outbound)
                    .put(JSONObject().put("type", "direct").put("tag", "direct"))
                    .put(JSONObject().put("type", "block").put("tag", "block"))
            )
            .put("route", route)
            .toString()
    }

    private fun routeRuleJson(rule: RouteRule): JSONObject? {
        val outbound = when (rule.action) {
            "DIRECT" -> "direct"
            "BLOCK" -> "block"
            else -> "proxy"
        }
        val matchValues = JSONArray().also { array -> rule.values.forEach(array::put) }
        val matcher = when (rule.matchType) {
            "DOMAIN_SUFFIX" -> "domain_suffix"
            "DOMAIN_KEYWORD" -> "domain_keyword"
            "IP_CIDR" -> "ip_cidr"
            "APP_PACKAGE" -> "package_name"
            else -> return null
        }
        return JSONObject()
            .put(matcher, matchValues)
            .put("outbound", outbound)
    }

    private fun routeExcludeAddresses(profile: VlessProfile): JSONArray {
        val cidrs = buildList {
            addAll(LAN_BYPASS_CIDRS)
            add("1.1.1.1/32")
            if (profile.host.isIpv4Address()) add("${profile.host}/32")
        }
        return JSONArray().also { array -> cidrs.distinct().forEach(array::put) }
    }

    private fun String.isIpv4Address(): Boolean {
        val parts = split(".")
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
        }
    }

    private const val BOOTSTRAP_DNS_TAG = "bootstrap"
    private const val REMOTE_DNS_TAG = "remote"

    private val LAN_BYPASS_CIDRS = listOf(
        "10.0.0.0/8",
        "100.64.0.0/10",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "224.0.0.0/4",
        "255.255.255.255/32",
        "fc00::/7",
        "fe80::/10",
        "ff00::/8"
    )
}
