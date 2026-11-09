package app.chspch.desktop

import org.json.JSONObject
import kotlin.test.*

class TelegramLoginLinksTest {
    private val id = "0123456789abcdef0123456789abcdef"
    private fun response() = JSONObject().put("challengeId", id)
        .put("telegramAppUrl", "tg://resolve?domain=test_bot&start=auth_$id")
        .put("telegramWebUrl", "https://t.me/test_bot?start=auth_$id")
    @Test fun `direct and browser links retain the same start payload`() {
        val links = TelegramLoginLinks.parse(response())
        assertEquals("tg", links.app.scheme)
        assertEquals("domain=test_bot&start=auth_$id", links.app.rawQuery)
        assertEquals("start=auth_$id", links.web.rawQuery)
    }
    @Test fun `plain bot link without challenge is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            TelegramLoginLinks.parse(response().put("telegramAppUrl", "tg://resolve?domain=test_bot"))
        }
    }
    @Test fun `mismatched challenge is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            TelegramLoginLinks.parse(response().put("telegramWebUrl", "https://t.me/test_bot?start=auth_other"))
        }
    }
}
