import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.timestamp
import java.math.BigDecimal
import java.time.Instant

// ==================== ПОЛЬЗОВАТЕЛИ ====================
object UsersTable : UUIDTable("users") {
    val email = varchar("email", 255).uniqueIndex()
    val username = varchar("username", 100).nullable()
    val passwordHash = varchar("password_hash", 255)
    val salt = varchar("salt", 64)
    val status = varchar("status", 20).default("PENDING")
    val emailVerified = bool("email_verified").default(false)
    val emailVerificationToken = varchar("email_verification_token", 255).nullable()
    val passwordResetToken = varchar("password_reset_token", 255).nullable()
    val twoFactorEnabled = bool("two_factor_enabled").default(false)
    val twoFactorSecret = varchar("two_factor_secret", 255).nullable()
    val createdAt = timestamp("created_at").default(Instant.now())
    val updatedAt = timestamp("updated_at").default(Instant.now())
    val lastLoginAt = timestamp("last_login_at").nullable()
    val lastLoginIp = varchar("last_login_ip", 45).nullable()
}

// ==================== УСТРОЙСТВА ====================
object DevicesTable : UUIDTable("devices") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val deviceFingerprint = varchar("device_fingerprint", 255)
    val publicKey = text("public_key")
    val deviceName = varchar("device_name", 100)
    val deviceType = varchar("device_type", 20)
    val platform = varchar("platform", 20)
    val osVersion = varchar("os_version", 50).nullable()
    val appVersion = varchar("app_version", 20).nullable()
    val isActive = bool("is_active").default(true)
    val isTrusted = bool("is_trusted").default(false)
    val pushToken = text("push_token").nullable()
    val lastConnectedAt = timestamp("last_connected_at").nullable()
    val lastIpAddress = varchar("last_ip_address", 45).nullable()
    val lastServerRegion = varchar("last_server_region", 10).nullable()
    val createdAt = timestamp("created_at").default(Instant.now())

    init {
        uniqueIndex(userId, deviceFingerprint)
    }
}

// ==================== СЕССИИ ====================
object SessionsTable : UUIDTable("sessions") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val deviceId = reference("device_id", DevicesTable, onDelete = ReferenceOption.CASCADE)
    val refreshToken = varchar("refresh_token", 500).uniqueIndex()
    val refreshTokenExpiry = timestamp("refresh_token_expiry")
    val accessTokenHash = varchar("access_token_hash", 64).nullable()
    val status = varchar("status", 20).default("ACTIVE")
    val ipAddress = varchar("ip_address", 45)
    val userAgent = text("user_agent").nullable()
    val country = varchar("country", 2).nullable()
    val city = varchar("city", 100).nullable()
    val createdAt = timestamp("created_at").default(Instant.now())
    val lastActivityAt = timestamp("last_activity_at").default(Instant.now())
    val expiresAt = timestamp("expires_at")
    val revokedAt = timestamp("revoked_at").nullable()
    val revokeReason = varchar("revoke_reason", 50).nullable()
}

// ==================== VPN СЕРВЕРЫ ====================
object ServersTable : UUIDTable("servers") {
    val name = varchar("name", 100)
    val hostname = varchar("hostname", 255)
    val ipAddress = varchar("ip_address", 45)
    val port = integer("port").default(51820)
    val publicKey = text("public_key")
    val country = varchar("country", 2)
    val city = varchar("city", 100)
    val latitude = double("latitude").nullable()
    val longitude = double("longitude").nullable()
    val capacityMbps = integer("capacity_mbps")
    val currentLoadPercent = integer("current_load_percent").default(0)
    val maxConnections = integer("max_connections").default(1000)
    val currentConnections = integer("current_connections").default(0)
    val supportsP2p = bool("supports_p2p").default(false)
    val supportsStreaming = bool("supports_streaming").default(false)
    val status = varchar("status", 20).default("ACTIVE")
    val isPremium = bool("is_premium").default(false)
    val createdAt = timestamp("created_at").default(Instant.now())
    val lastHealthCheck = timestamp("last_health_check").nullable()
}

// ==================== ПОЛИТИКИ МАРШРУТИЗАЦИИ ====================
object PoliciesTable : UUIDTable("policies") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 100)
    val description = text("description").nullable()
    val defaultMode = varchar("default_mode", 20).default("VPN_ALL")
    val isActive = bool("is_active").default(false)
    val deviceId = reference("device_id", DevicesTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val createdAt = timestamp("created_at").default(Instant.now())
    val updatedAt = timestamp("updated_at").default(Instant.now())
}

// ==================== ПРАВИЛА ДЛЯ ПРИЛОЖЕНИЙ ====================
object AppRulesTable : UUIDTable("app_rules") {
    val policyId = reference("policy_id", PoliciesTable, onDelete = ReferenceOption.CASCADE)
    val appIdentifier = varchar("app_identifier", 255)
    val appName = varchar("app_name", 100).nullable()
    val platform = varchar("platform", 20)
    val action = varchar("action", 20) // VPN, DIRECT, BLOCK
    val isEnabled = bool("is_enabled").default(true)
    val priority = integer("priority").default(0)
    val createdAt = timestamp("created_at").default(Instant.now())

    init {
        uniqueIndex(policyId, appIdentifier, platform)
    }
}

// ==================== ПРАВИЛА ДЛЯ ДОМЕНОВ ====================
object DomainRulesTable : UUIDTable("domain_rules") {
    val policyId = reference("policy_id", PoliciesTable, onDelete = ReferenceOption.CASCADE)
    val domainPattern = varchar("domain_pattern", 255)
    val matchType = varchar("match_type", 20).default("SUFFIX")
    val action = varchar("action", 20)
    val description = varchar("description", 255).nullable()
    val isEnabled = bool("is_enabled").default(true)
    val priority = integer("priority").default(0)
    val createdAt = timestamp("created_at").default(Instant.now())
}

// ==================== РОТАЦИЯ КЛЮЧЕЙ ====================
object KeyRotationTable : UUIDTable("key_rotations") {
    val deviceId = reference("device_id", DevicesTable, onDelete = ReferenceOption.CASCADE)
    val publicKey = text("public_key")
    val keyVersion = integer("key_version")
    val status = varchar("status", 20).default("PENDING")
    val rotationReason = varchar("rotation_reason", 50)
    val createdAt = timestamp("created_at").default(Instant.now())
    val activatedAt = timestamp("activated_at").nullable()
    val deprecatedAt = timestamp("deprecated_at").nullable()
    val expiresAt = timestamp("expires_at")

    init {
        uniqueIndex(deviceId, keyVersion)
    }
}

// ==================== ТАРИФНЫЕ ПЛАНЫ ====================
object PlansTable : UUIDTable("plans") {
    val code = varchar("code", 50).uniqueIndex()
    val name = varchar("name", 100)
    val description = text("description").nullable()
    val priceMonthly = decimal("price_monthly", 10, 2)
    val priceYearly = decimal("price_yearly", 10, 2)
    val currency = varchar("currency", 3).default("USD")
    val maxDevices = integer("max_devices")
    val maxBandwidthGb = integer("max_bandwidth_gb").nullable()
    val features = text("features")
    val hasPremiumServers = bool("has_premium_servers").default(false)
    val hasP2p = bool("has_p2p").default(false)
    val isActive = bool("is_active").default(true)
    val isPublic = bool("is_public").default(true)
    val sortOrder = integer("sort_order").default(0)
    val createdAt = timestamp("created_at").default(Instant.now())
}

// ==================== ПОДПИСКИ ====================
object SubscriptionsTable : UUIDTable("subscriptions") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val planId = reference("plan_id", PlansTable)
    val status = varchar("status", 20).default("ACTIVE")
    val billingPeriod = varchar("billing_period", 20)
    val startDate = timestamp("start_date")
    val endDate = timestamp("end_date")
    val trialEndDate = timestamp("trial_end_date").nullable()
    val externalSubscriptionId = varchar("external_subscription_id", 255).nullable()
    val paymentProvider = varchar("payment_provider", 50).nullable()
    val autoRenew = bool("auto_renew").default(true)
    val cancelledAt = timestamp("cancelled_at").nullable()
    val cancelReason = varchar("cancel_reason", 255).nullable()
    val bandwidthUsedGb = decimal("bandwidth_used_gb", 10, 2).default(BigDecimal.ZERO)
    val createdAt = timestamp("created_at").default(Instant.now())
}

// ==================== АУДИТ ЛОГИ ====================
object AuditLogsTable : LongIdTable("audit_logs") {
    val userId = uuid("user_id").nullable().index()
    val sessionId = uuid("session_id").nullable()
    val actorType = varchar("actor_type", 20)
    val actorIp = varchar("actor_ip", 45)
    val actorUserAgent = text("actor_user_agent").nullable()
    val action = varchar("action", 50).index()
    val resourceType = varchar("resource_type", 50).nullable()
    val resourceId = varchar("resource_id", 36).nullable()
    val status = varchar("status", 20)
    val errorCode = varchar("error_code", 50).nullable()
    val errorMessage = text("error_message").nullable()
    val details = text("details").nullable()
    val timestamp = timestamp("timestamp").default(Instant.now()).index()
}

// ==================== МЕТРИКИ ====================
object MetricsTable : LongIdTable("metrics") {
    val userId = uuid("user_id").nullable().index()
    val deviceId = uuid("device_id").nullable()
    val serverId = uuid("server_id").nullable()
    val sessionId = uuid("session_id").nullable()
    val metricType = varchar("metric_type", 50).index()
    val value = long("value")
    val unit = varchar("unit", 20)
    val timestamp = timestamp("timestamp").index()
    val periodStart = timestamp("period_start").nullable()
    val periodEnd = timestamp("period_end").nullable()
    val aggregationLevel = varchar("aggregation_level", 20).default("RAW")
    val country = varchar("country", 2).nullable()
    val platform = varchar("platform", 20).nullable()
}

// Список всех таблиц для создания/удаления
val allTables = arrayOf(
    UsersTable, ServersTable, DevicesTable, SessionsTable,
    PoliciesTable, AppRulesTable, DomainRulesTable, KeyRotationTable,
    PlansTable, SubscriptionsTable, AuditLogsTable, MetricsTable
)
