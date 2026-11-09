package com.example.modules.vpn

/** Only explicitly configured restricted inbounds may be used before Telegram login. */
internal fun <T> selectGuestNode(ids: List<String>, requested: String?, healthy: (String) -> T?): T? {
    val ordered = listOfNotNull(requested?.takeIf { it in ids }) + ids
    return ordered.distinct().firstNotNullOfOrNull(healthy)
}
