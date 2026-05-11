package com.example.security

import com.example.common.ApiException
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.jwt.JWTPrincipal
import java.util.UUID

fun JWTPrincipal.requireUserId(): UUID {
    val sub = payload.subject ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing subject")
    return UUID.fromString(sub)
}

fun JWTPrincipal.requireRole(): String {
    return payload.getClaim("role").asString()
        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing role")
}

fun JWTPrincipal.deviceIdOrNull(): UUID? {
    val did = payload.getClaim("did").asString() ?: return null
    return UUID.fromString(did)
}
