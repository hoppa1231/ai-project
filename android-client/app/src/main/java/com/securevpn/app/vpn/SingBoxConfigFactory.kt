package com.securevpn.app.vpn

import com.securevpn.app.BuildConfig
import com.securevpn.app.data.RouteRule
import com.securevpn.app.data.RoutingPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

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

        apiBaseHost()?.let { host ->
            routeRules.put(
                JSONObject()
                    .put("domain_suffix", JSONArray().put(host))
                    .put("outbound", "direct")
            )
        }

        val routeRuleSets = geoipRouteRuleSets(policy)

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
        if (routeRuleSets.length() > 0) {
            route.put("rule_set", routeRuleSets)
        }

        if (profile.host.isIpv4Address()) {
            routeRules.put(
                JSONObject()
                    .put("ip_cidr", JSONArray().put("${profile.host}/32"))
                    .put("outbound", "direct")
            )
        }

        val config = JSONObject()
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
        if (routeRuleSets.length() > 0) {
            config.put(
                "experimental",
                JSONObject().put(
                    "cache_file",
                    JSONObject().put("enabled", true)
                )
            )
        }

        return config.toString()
    }

    private fun apiBaseHost(): String? {
        return runCatching { URI(BuildConfig.API_BASE_URL).host }
            .getOrNull()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
    }

    private fun routeRuleJson(rule: RouteRule): JSONObject? {
        val outbound = when (rule.action) {
            "DIRECT" -> "direct"
            "BLOCK" -> "block"
            else -> "proxy"
        }
        val values = if (rule.matchType == "GEOIP") {
            rule.values.mapNotNull(::normalizedGeoipCode).map { "geoip-$it" }
        } else {
            rule.values
        }
        if (values.isEmpty()) return null
        val matchValues = JSONArray().also { array -> values.forEach(array::put) }
        val matcher = when (rule.matchType) {
            "DOMAIN" -> "domain"
            "DOMAIN_SUFFIX" -> "domain_suffix"
            "DOMAIN_KEYWORD" -> "domain_keyword"
            "IP_CIDR" -> "ip_cidr"
            "APP_PACKAGE" -> "package_name"
            "GEOIP" -> "rule_set"
            else -> return null
        }
        return JSONObject()
            .put(matcher, matchValues)
            .put("outbound", outbound)
    }

    private fun geoipRouteRuleSets(policy: RoutingPolicy): JSONArray {
        val codes = policy.routeRules
            .filter { it.enabled && it.matchType == "GEOIP" }
            .flatMap { it.values }
            .mapNotNull(::normalizedGeoipCode)
            .distinct()
        return JSONArray().also { array ->
            codes.forEach { code ->
                val tag = "geoip-$code"
                array.put(
                    JSONObject()
                        .put("type", "remote")
                        .put("tag", tag)
                        .put("format", "binary")
                        .put("url", GEOIP_RULE_SET_URL_OVERRIDES[code] ?: "$GEOIP_RULE_SET_BASE_URL/$tag.srs")
                        .put("download_detour", "proxy")
                )
            }
        }
    }

    private fun normalizedGeoipCode(value: String): String? {
        val normalized = value.lowercase().removePrefix("geoip-")
        return normalized.takeIf { GEOIP_RULE_SET_REGEX.matches(it) }
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
    private const val GEOIP_RULE_SET_BASE_URL = "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set"
    private val GEOIP_RULE_SET_REGEX = Regex("^[a-z]{2}(-[a-z0-9]+)*$")
    private val GEOIP_RULE_SET_URL_OVERRIDES = mapOf(
        "ru-blocked" to "https://raw.githubusercontent.com/runetfreedom/russia-v2ray-rules-dat/release/sing-box/rule-set-geoip/geoip-ru-blocked.srs"
    )

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
