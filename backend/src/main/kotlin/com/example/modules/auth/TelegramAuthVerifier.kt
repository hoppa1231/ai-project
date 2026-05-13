package com.example.modules.auth

import com.example.common.ApiException
import io.ktor.http.HttpStatusCode
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class VerifiedTelegramAuth(
    val id: Long,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
    val photoUrl: String?,
    val authDate: Instant
)

object TelegramAuthVerifier {
    private val maxAge = Duration.ofHours(24)

    fun verify(botToken: String, payload: TelegramAuthPayload): VerifiedTelegramAuth {
        if (botToken.isBlank()) {
            throw ApiException(HttpStatusCode.ServiceUnavailable, "TELEGRAM_AUTH_DISABLED", "Telegram auth is not configured")
        }

        val authInstant = Instant.ofEpochSecond(payload.authDate)
        if (authInstant.isBefore(Instant.now().minus(maxAge))) {
            throw ApiException(HttpStatusCode.Unauthorized, "TELEGRAM_AUTH_EXPIRED", "Telegram auth payload expired")
        }

        val fields = buildMap {
            put("id", payload.id.toString())
            payload.firstName?.let { put("first_name", it) }
            payload.lastName?.let { put("last_name", it) }
            payload.username?.let { put("username", it) }
            payload.photoUrl?.let { put("photo_url", it) }
            put("auth_date", payload.authDate.toString())
        }
        val checkString = fields.toSortedMap().entries.joinToString("\n") { (key, value) -> "$key=$value" }
        val secretKey = MessageDigest.getInstance("SHA-256").digest(botToken.toByteArray(Charsets.UTF_8))
        val actual = hmacSha256(secretKey, checkString).toHex()
        if (!MessageDigest.isEqual(actual.toByteArray(Charsets.UTF_8), payload.hash.lowercase().toByteArray(Charsets.UTF_8))) {
            throw ApiException(HttpStatusCode.Unauthorized, "INVALID_TELEGRAM_AUTH", "Invalid Telegram auth signature")
        }

        return VerifiedTelegramAuth(
            id = payload.id,
            username = payload.username,
            firstName = payload.firstName,
            lastName = payload.lastName,
            photoUrl = payload.photoUrl,
            authDate = authInstant
        )
    }

    private fun hmacSha256(key: ByteArray, value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
