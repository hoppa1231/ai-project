package com.example.config

import com.example.agent.AgentXrayAdminClient
import com.example.db.DatabaseBundle
import com.example.db.DatabaseFactory
import com.example.db.repo.AuditRepository
import com.example.db.repo.DeviceRepository
import com.example.db.repo.NodeRepository
import com.example.db.repo.NodeClientInventoryRepository
import com.example.db.repo.PolicyRepository
import com.example.db.repo.QuotaRepository
import com.example.db.repo.RefreshTokenRepository
import com.example.db.repo.UserRepository
import com.example.db.repo.VpnRepository
import com.example.security.JwtService
import com.example.security.PasswordHasher
import com.example.security.RateLimitService
import com.example.xray.GrpcXrayAdminClient
import com.example.xray.StubXrayAdminClient
import com.example.xray.XrayAdminClient

class AppContext(
    val config: AppConfig,
    val db: DatabaseBundle,
    val users: UserRepository,
    val devices: DeviceRepository,
    val nodes: NodeRepository,
    val nodeClients: NodeClientInventoryRepository,
    val policies: PolicyRepository,
    val quota: QuotaRepository,
    val refreshTokens: RefreshTokenRepository,
    val vpn: VpnRepository,
    val audit: AuditRepository,
    val jwt: JwtService,
    val passwordHasher: PasswordHasher,
    val rateLimit: RateLimitService,
    val xray: XrayAdminClient
)

fun buildContext(config: AppConfig): AppContext {
    val db = DatabaseFactory.create(config)
    val xrayClient = when (config.xrayMode.lowercase()) {
        "agent" -> AgentXrayAdminClient(
            scheme = config.agentScheme,
            basePath = config.agentBasePath,
            token = config.agentToken
        )
        "grpc" -> GrpcXrayAdminClient()
        else -> StubXrayAdminClient()
    }

    return AppContext(
        config = config,
        db = db,
        users = UserRepository(db.dsl),
        devices = DeviceRepository(db.dsl),
        nodes = NodeRepository(db.dsl),
        nodeClients = NodeClientInventoryRepository(db.dsl),
        policies = PolicyRepository(db.dsl),
        quota = QuotaRepository(db.dsl),
        refreshTokens = RefreshTokenRepository(db.dsl),
        vpn = VpnRepository(db.dsl),
        audit = AuditRepository(db.dsl),
        jwt = JwtService(config.jwt),
        passwordHasher = PasswordHasher(),
        rateLimit = RateLimitService(),
        xray = xrayClient
    )
}
