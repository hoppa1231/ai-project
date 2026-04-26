package com.example.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val details: JsonElement? = null,
    val requestId: String? = null
)
