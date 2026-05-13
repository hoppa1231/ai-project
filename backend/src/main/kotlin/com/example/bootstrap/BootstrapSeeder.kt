package com.example.bootstrap

import com.example.common.Hashing
import com.example.common.PolicyHashing
import com.example.config.AppContext
import com.example.db.NodeEntity
import com.example.modules.vpn.VpnConfigRenderer
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

object BootstrapSeeder {
    private val log = LoggerFactory.getLogger(BootstrapSeeder::class.java)
    private const val defaultAdminEmail = "admin@securevpn.local"
    private const val defaultAdminPassword = "Admin12345!"
    private const val samplePassword = "User12345!"
    private const val flow = "xtls-rprx-vision"

    fun run(context: AppContext) {
        val adminEmail = System.getenv("ADMIN_EMAIL")?.takeIf { it.isNotBlank() } ?: defaultAdminEmail
        val adminPassword = System.getenv("ADMIN_PASSWORD")?.takeIf { it.isNotBlank() } ?: defaultAdminPassword

        ensureAdmin(context, adminEmail, adminPassword)

        val seedEnabled = System.getenv("SEED_SYNTHETIC")?.lowercase() == "true"
        if (seedEnabled) {
            seedSynthetic(context)
        }
    }

    private fun ensureAdmin(context: AppContext, email: String, password: String) {
        val existing = context.users.findByEmail(email)
        val admin = existing ?: context.users.createRegistered(email, context.passwordHasher.hash(password))
        if (admin.role != "ADMIN") {
            context.users.setRole(admin.id, "ADMIN")
        }
        context.policies.createDefault(admin.id, defaultPolicyHash())
        log.info("Bootstrap admin is ready: {}", email)
    }

    private fun seedSynthetic(context: AppContext) {
        val nodes = seedNodes(context)
        if (nodes.isEmpty()) {
            log.warn("Synthetic seed skipped configs because no nodes are available")
            return
        }

        repeat(5) { index ->
            val email = "seed.user.${index + 1}@securevpn.local"
            val user = context.users.findByEmail(email)
                ?: context.users.createRegistered(email, context.passwordHasher.hash(samplePassword))
            context.policies.createDefault(user.id, defaultPolicyHash())

            val fingerprint = Hashing.sha256Hex("${context.config.hashPepper}:seed-device-${index + 1}")
            val device = context.devices.bindDevice(
                userId = user.id,
                fingerprintHash = fingerprint,
                deviceName = "Seed Android ${index + 1}",
                platform = "android",
                appVersion = "0.1.0",
                publicKey = null
            )

            val hasConfig = context.vpn.findActiveByDevice(user.id, device.id) != null
            if (!hasConfig) {
                val node = nodes[index % nodes.size]
                val expiresAt = Instant.now().plusSeconds(30L * 24L * 60L * 60L)
                val configId = context.vpn.insertProvisioning(
                    userId = user.id,
                    deviceId = device.id,
                    nodeId = node.id,
                    idempotencyKey = "bootstrap-seed-${index + 1}",
                    expiresAt = expiresAt
                )
                val vlessUuid = UUID.nameUUIDFromBytes("seed-vless-${index + 1}".toByteArray())
                val emailTag = "seed_${index + 1}@cp.local"
                val client = context.vpn.createClient(user.id, device.id, node.id, emailTag, vlessUuid, flow, expiresAt)
                val policy = context.policies.getCurrent(user.id) ?: error("missing policy for seeded user")
                val rendered = VpnConfigRenderer.render(node, vlessUuid, flow, policy)
                context.vpn.finalizeIssued(configId, client.id, rendered.vlessUri, rendered.configJson, rendered.hash, Instant.now())
            }
        }

        log.info("Synthetic users are ready: seed.user.1..5@securevpn.local / {}", samplePassword)
    }

    private fun seedNodes(context: AppContext): List<NodeEntity> {
        val specs = listOf(
            NodeSpec("Moscow Seed 01", "ru", "RU", "msk-seed-01.vpn.example.com", "203.0.113.10", "msk-vless-01"),
            NodeSpec("Frankfurt Seed 01", "de", "DE", "fra-seed-01.vpn.example.com", "203.0.113.20", "fra-vless-01"),
            NodeSpec("Amsterdam Seed 01", "nl", "NL", "ams-seed-01.vpn.example.com", "203.0.113.30", "ams-vless-01"),
            NodeSpec("Helsinki Seed 01", "fi", "FI", "hel-seed-01.vpn.example.com", "203.0.113.40", "hel-vless-01"),
            NodeSpec("Istanbul Seed 01", "tr", "TR", "ist-seed-01.vpn.example.com", "203.0.113.50", "ist-vless-01"),
            NodeSpec("Almaty Seed 01", "kz", "KZ", "ala-seed-01.vpn.example.com", "203.0.113.60", "ala-vless-01")
        )

        specs.forEachIndexed { index, spec ->
            context.db.dsl.execute(
                """
                INSERT INTO nodes (
                    name, region, country_code, hostname, public_address, public_port,
                    api_host, api_port, inbound_tag, reality_server_name, reality_public_key,
                    reality_short_id, reality_fingerprint, reality_alpn, weight, max_clients,
                    status, health, health_message, last_health_check_at
                )
                VALUES (?, ?, ?, ?, ?, 443, ?, 10085, ?, 'www.microsoft.com', ?, ?, 'chrome',
                        '{"h2","http/1.1"}'::text[], 100, 10000, 'ACTIVE', 'HEALTHY', 'bootstrap seed', now())
                ON CONFLICT (name) DO NOTHING
                """.trimIndent(),
                spec.name,
                spec.region,
                spec.countryCode,
                spec.hostname,
                spec.publicAddress,
                "127.0.0.${index + 1}",
                spec.inboundTag,
                "seedRealityPublicKey${index + 1}xxxxxxxxxxxxxxxxxxxxxxxx",
                "%08x".format(index + 1)
            )
        }

        return specs.mapNotNull { spec ->
            val rec = context.db.dsl.fetchOne("SELECT id FROM nodes WHERE name = ?", spec.name)
            rec?.get("id", UUID::class.java)?.let { context.nodes.findById(it) }
        }
    }

    private fun defaultPolicyHash(): String {
        return PolicyHashing.hash("VPN", emptyList(), emptyList(), emptyList(), emptyList())
    }

    private data class NodeSpec(
        val name: String,
        val region: String,
        val countryCode: String,
        val hostname: String,
        val publicAddress: String,
        val inboundTag: String
    )
}
