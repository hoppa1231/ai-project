package com.example.modules.auth

import com.example.config.AppContext
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class TelegramAppLoginChallenge(
    val id: String,
    val telegramAppUrl: String,
    val telegramWebUrl: String,
    val expiresAt: Instant
)

data class TelegramAppLoginDevice(
    val fingerprint: String,
    val name: String,
    val platform: String,
    val appVersion: String?,
    val publicKey: String?
)

class TelegramAppLoginService(
    private val botToken: String,
    botUsername: String
) {
    private val logger = LoggerFactory.getLogger(TelegramAppLoginService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val botUsername = botUsername.removePrefix("@")
    private val pending = ConcurrentHashMap<String, PendingLogin>()
    private var nextOffset: Long? = null

    fun create(device: TelegramAppLoginDevice): TelegramAppLoginChallenge {
        val id = UUID.randomUUID().toString().replace("-", "")
        val expiresAt = Instant.now().plusSeconds(300)
        pending[id] = PendingLogin(device = device, expiresAt = expiresAt)
        return TelegramAppLoginChallenge(
            id = id,
            telegramAppUrl = "tg://resolve?domain=$botUsername&start=auth_$id",
            telegramWebUrl = "https://t.me/$botUsername?start=auth_$id",
            expiresAt = expiresAt
        )
    }

    fun get(id: String): PendingTelegramLogin? {
        val current = pending[id] ?: return null
        if (current.expiresAt.isBefore(Instant.now())) {
            pending.remove(id)
            return null
        }
        return PendingTelegramLogin(
            device = current.device,
            verified = current.verified,
            expiresAt = current.expiresAt
        )
    }

    fun consume(id: String) {
        pending.remove(id)
    }

    suspend fun pollOnce() {
        if (botToken.isBlank()) return
        cleanupExpired()
        val query = buildList {
            add("timeout=20")
            add("allowed_updates=${encode("""["message"]""")}")
            nextOffset?.let { add("offset=$it") }
        }.joinToString("&")
        val response = runCatching {
            requestTelegram("getUpdates?$query")
        }.getOrElse { error ->
            logger.warn("Telegram getUpdates failed: {}", error.message)
            return
        }
        val updates = runCatching { json.decodeFromString<TelegramUpdatesResponse>(response) }.getOrElse { error ->
            logger.warn("Telegram getUpdates parse failed: {}", error.message)
            return
        }
        updates.result.forEach { update ->
            nextOffset = maxOf(nextOffset ?: 0L, update.updateId + 1)
            handleUpdate(update)
        }
    }

    private fun handleUpdate(update: TelegramUpdate) {
        val message = update.message ?: return
        val from = message.from ?: return
        if (from.isBot) return
        val challengeId = extractChallengeId(message.text ?: return) ?: return
        val pendingLogin = pending[challengeId] ?: return
        if (pendingLogin.expiresAt.isBefore(Instant.now())) {
            pending.remove(challengeId)
            return
        }
        pendingLogin.verified = VerifiedTelegramAuth(
            id = from.id,
            username = from.username,
            firstName = from.firstName,
            lastName = from.lastName,
            photoUrl = null,
            authDate = Instant.now()
        )
        message.chat?.id?.let {
            runCatching {
                requestTelegram("sendMessage?chat_id=$it&text=${encode("Вход подтвержден. Вернитесь в приложение.")}")
            }
        }
    }

    private fun extractChallengeId(text: String): String? {
        val afterStart = text.substringAfter("/start", "").trim()
        val payload = if (afterStart.startsWith("@$botUsername")) {
            afterStart.substringAfter(' ', "").trim()
        } else {
            afterStart
        }
        if (!payload.startsWith("auth_")) return null
        return payload.removePrefix("auth_").takeIf { it.matches(Regex("[a-f0-9]{32}")) }
    }

    private fun cleanupExpired() {
        val now = Instant.now()
        pending.entries.removeIf { it.value.expiresAt.isBefore(now) }
    }

    private fun requestTelegram(methodAndQuery: String): String {
        val connection = URL("https://api.telegram.org/bot$botToken/$methodAndQuery").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 25_000
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code: $body")
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private data class PendingLogin(
        val device: TelegramAppLoginDevice,
        val expiresAt: Instant,
        @Volatile var verified: VerifiedTelegramAuth? = null
    )
}

data class PendingTelegramLogin(
    val device: TelegramAppLoginDevice,
    val verified: VerifiedTelegramAuth?,
    val expiresAt: Instant
)

fun Application.startTelegramAppLoginWorker(context: AppContext) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    monitor.subscribe(ApplicationStopping) {
        scope.cancel()
    }
    scope.launch {
        while (isActive) {
            context.telegramAppLogins.pollOnce()
            delay(1_000)
        }
    }
}

@Serializable
private data class TelegramUpdatesResponse(
    val ok: Boolean,
    val result: List<TelegramUpdate> = emptyList()
)

@Serializable
private data class TelegramUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TelegramMessage? = null
)

@Serializable
private data class TelegramMessage(
    val text: String? = null,
    val from: TelegramUser? = null,
    val chat: TelegramChat? = null
)

@Serializable
private data class TelegramChat(val id: Long)

@Serializable
private data class TelegramUser(
    val id: Long,
    @SerialName("is_bot") val isBot: Boolean = false,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    val username: String? = null
)
