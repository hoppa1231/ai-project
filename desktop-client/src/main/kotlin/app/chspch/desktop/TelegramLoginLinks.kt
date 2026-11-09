package app.chspch.desktop

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** Keep the server-issued challenge intact when switching from web to Telegram. */
data class TelegramLoginLinks(val challengeId: String, val app: URI, val web: URI) {
    companion object {
        fun parse(response: JSONObject): TelegramLoginLinks {
            val id = response.getString("challengeId")
            require(id.matches(Regex("[a-f0-9]{32}"))) { "Сервер вернул неверную ссылку входа" }
            val app = URI(response.getString("telegramAppUrl"))
            val web = URI(response.getString("telegramWebUrl"))
            fun params(uri: URI) = uri.rawQuery.orEmpty().split('&').associate {
                val parts = it.split('=', limit = 2)
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            }
            require(app.scheme == "tg" && app.host == "resolve" && web.scheme == "https" && web.host == "t.me")
            require(params(app)["start"] == "auth_$id" && params(web)["start"] == "auth_$id") { "Ссылка не соответствует коду входа" }
            require(params(app)["domain"] == web.path.removePrefix("/"))
            return TelegramLoginLinks(id, app, web)
        }
    }
}
