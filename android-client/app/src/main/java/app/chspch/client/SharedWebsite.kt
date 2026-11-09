package app.chspch.client

import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/** Only the normalized destination leaves the parser; paths, queries and credentials are discarded. */
data class SharedWebsite(val value: String, val matchType: String)

object SharedWebsiteParser {
    private val urls = Regex("https?://[^\\s<>\"\\p{Cntrl}]+", RegexOption.IGNORE_CASE)
    private val domain = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$")

    fun parse(text: String): SharedWebsite {
        require(text.length <= 16_384) { "Слишком длинный текст. Поделитесь одной ссылкой." }
        val candidates = urls.findAll(text).map { it.value.trimEnd('.', ',', ';', '!', ')') }.toList()
        require(candidates.isNotEmpty()) { "Не найдена ссылка HTTP или HTTPS." }
        val destinations = candidates.map(::destination).distinct()
        require(destinations.size == 1) { "В тексте несколько сайтов. Поделитесь одной ссылкой." }
        return destinations.single()
    }

    private fun destination(url: String): SharedWebsite {
        val uri = runCatching { URI(url) }.getOrElse { throw IllegalArgumentException("Некорректная ссылка.") }
        require(uri.scheme.lowercase() in setOf("http", "https")) { "Нужна ссылка HTTP или HTTPS." }
        require(uri.rawUserInfo == null && '@' !in uri.rawAuthority.orEmpty()) { "Ссылки с логином и паролем не поддерживаются." }
        val authority = uri.rawAuthority ?: throw IllegalArgumentException("В ссылке отсутствует сайт.")
        val portSuffix = if (authority.startsWith('[')) authority.substringAfter(']', "")
            else if (':' in authority) ":" + authority.substringAfter(':') else ""
        require(portSuffix.isEmpty() || (portSuffix.startsWith(':') && portSuffix.drop(1).all(Char::isDigit) && portSuffix.drop(1).toIntOrNull() in 1..65535)) { "Некорректный порт в ссылке." }
        val host = if (authority.startsWith('[')) {
            val closing = authority.indexOf(']')
            require(closing > 0 && authority.substring(closing + 1).let { it.isEmpty() || it.matches(Regex(":[0-9]{1,5}")) }) { "Некорректный сетевой адрес версии 6." }
            authority.substring(1, closing)
        } else {
            require(authority.count { it == ':' } <= 1) { "Некорректный адрес сайта." }
            authority.substringBefore(':')
        }
        if (':' in host) {
            require(host.matches(Regex("[0-9a-fA-F:]+"))) { "Некорректный сетевой адрес версии 6." }
            require(runCatching { InetAddress.getByName(host) is Inet6Address }.getOrDefault(false)) { "Некорректный сетевой адрес версии 6." }
            return SharedWebsite("${host.lowercase()}/128", "IP_CIDR")
        }
        val normalized = runCatching { IDN.toASCII(host.trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase() }
            .getOrElse { throw IllegalArgumentException("Некорректный домен.") }
        if (normalized.matches(Regex("[0-9.]+"))) {
            val parts = normalized.split('.')
            require(parts.size == 4 && parts.all { it.isNotEmpty() && it.toIntOrNull() in 0..255 && (it == "0" || !it.startsWith('0')) }) { "Некорректный сетевой адрес." }
            return SharedWebsite("$normalized/32", "IP_CIDR")
        }
        require(normalized.length <= 253 && domain.matches(normalized)) { "Некорректный домен." }
        require(normalized.substringAfterLast('.') !in setOf("ru", "su", "xn--p1ai")) { "Домены .ru, .рф и .su нельзя добавлять в ВПН." }
        return SharedWebsite(normalized, "DOMAIN_SUFFIX")
    }
}
