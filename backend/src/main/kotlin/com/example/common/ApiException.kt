package com.example.common

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonElement

class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val details: JsonElement? = null
) : RuntimeException(message)
