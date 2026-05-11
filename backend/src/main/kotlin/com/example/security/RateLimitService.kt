package com.example.security

import com.example.common.ApiException
import io.ktor.http.HttpStatusCode
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

data class LimitRule(val maxRequests: Int, val window: Duration)

class RateLimitService {
    private data class Counter(var windowStartMs: Long, var count: Int)

    private val storage = ConcurrentHashMap<String, Counter>()

    fun enforce(key: String, rule: LimitRule, code: String = "RATE_LIMITED") {
        val now = System.currentTimeMillis()
        val counter = storage.compute(key) { _, current ->
            if (current == null || now - current.windowStartMs >= rule.window.toMillis()) {
                Counter(now, 1)
            } else {
                current.count += 1
                current
            }
        }!!

        if (counter.count > rule.maxRequests) {
            throw ApiException(
                status = HttpStatusCode.TooManyRequests,
                code = code,
                message = "Too many requests"
            )
        }
    }
}
