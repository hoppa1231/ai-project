package com.example.db

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class UserEntity(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val role: String,
    val status: String,
    val accountType: String
)

data class DeviceEntity(
    val id: UUID,
    val userId: UUID,
    val fingerprintHash: String,
    val deviceName: String,
    val platform: String,
    val status: String
)

data class PublicNode(
    val id: UUID,
    val name: String,
    val region: String,
    val countryCode: String,
    val hostname: String,
    val port: Int,
    val health: String,
    val load: Int,
    val fingerprint: String,
    val alpn: List<String>
)

data class NodeEntity(
    val id: UUID,
    val name: String,
    val region: String,
    val countryCode: String,
    val hostname: String,
    val publicAddress: String,
    val publicPort: Int,
    val apiHost: String,
    val apiPort: Int,
    val inboundTag: String,
    val realityServerName: String,
    val realityPublicKey: String,
    val realityShortId: String,
    val realityFingerprint: String,
    val realityAlpn: List<String>,
    val status: String,
    val health: String,
    val weight: Int,
    val load: Int,
    val maxClients: Int
)

data class PolicyEntity(
    val userId: UUID,
    val version: Int,
    val defaultRoute: String,
    val includeApps: List<String>,
    val excludeApps: List<String>,
    val includeDomains: List<String>,
    val excludeDomains: List<String>,
    val hash: String,
    val updatedAt: Instant
)

data class RefreshTokenEntity(
    val jti: UUID,
    val userId: UUID,
    val deviceId: UUID,
    val tokenHash: String,
    val expiresAt: Instant,
    val revokedAt: Instant?
)

data class IssuedConfigEntity(
    val id: UUID,
    val userId: UUID,
    val deviceId: UUID,
    val nodeId: UUID,
    val clientId: UUID?,
    val routeMode: String,
    val status: String,
    val vlessUri: String?,
    val configJson: String?,
    val expiresAt: Instant,
    val issuedAt: Instant?,
    val revokedAt: Instant?
)

data class IssuedConfigHopEntity(
    val id: UUID,
    val configId: UUID,
    val hopIndex: Int,
    val role: String,
    val nodeId: UUID,
    val clientId: UUID?,
    val vlessUuid: UUID,
    val flow: String?,
    val status: String,
    val createdAt: Instant,
    val revokedAt: Instant?
)

data class ClientEntity(
    val id: UUID,
    val userId: UUID,
    val deviceId: UUID,
    val nodeId: UUID,
    val email: String,
    val vlessUuid: UUID,
    val status: String,
    val flow: String?
)

data class QuotaSnapshot(
    val cycleStart: LocalDate,
    val cycleEnd: LocalDate,
    val freeBytes: Long,
    val purchasedBytes: Long,
    val usedBytes: Long,
    val remainingBytes: Long
)
