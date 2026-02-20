import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

fun main() {
    // Database connection
    val config = HikariConfig().apply {
        jdbcUrl = "jdbc:postgresql://localhost:5432/vpn_db"
        driverClassName = "org.postgresql.Driver"
        username = "postgres"
        password = "FdfyufhlJvcr1"
        maximumPoolSize = 3
    }
    Database.connect(HikariDataSource(config))

    // Create tables
    transaction {
        SchemaUtils.drop(*allTables.reversedArray())
        SchemaUtils.create(*allTables)
        println("✅ Tables created\n")
    }

    // Demo
    transaction {
        // 1. User
        val userId = UsersTable.insertAndGetId {
            it[email] = "test@example.com"
            it[username] = "testuser"
            it[passwordHash] = "hash123"
            it[salt] = "salt123"
            it[status] = "ACTIVE"
            it[emailVerified] = true
        }.value
        println("👤 User: $userId")


        // 2. Device
        val deviceId = DevicesTable.insertAndGetId {
            it[DevicesTable.userId] = userId
            it[deviceFingerprint] = "fp-123"
            it[publicKey] = "PUBLIC_KEY_123"
            it[deviceName] = "iPhone 15"
            it[deviceType] = "MOBILE"
            it[platform] = "IOS"
        }.value
        println("📱 Device: $deviceId")

        // 3. Session
        SessionsTable.insert {
            it[SessionsTable.userId] = userId
            it[SessionsTable.deviceId] = deviceId
            it[refreshToken] = "token-${UUID.randomUUID()}"
            it[refreshTokenExpiry] = Instant.now().plus(30, ChronoUnit.DAYS)
            it[ipAddress] = "192.168.1.1"
            it[expiresAt] = Instant.now().plus(30, ChronoUnit.DAYS)
        }
        println("🔐 Session created")

        // 4. Servers
        listOf(
            Triple("Frankfurt", "DE", 25),
            Triple("Amsterdam", "NL", 40),
            Triple("New York", "US", 60)
        ).forEach { (city, country, load) ->
            ServersTable.insert {
                it[name] = "$city #1"
                it[hostname] = "vpn-${country.lowercase()}.example.com"
                it[ipAddress] = "10.0.0.${(1..255).random()}"
                it[publicKey] = "SERVER_KEY_$country"
                it[ServersTable.country] = country
                it[ServersTable.city] = city
                it[capacityMbps] = 1000
                it[currentLoadPercent] = load
            }
        }
        println("🌍 Servers:")
        ServersTable.selectAll().forEach {
            println("   ${it[ServersTable.name]}: ${it[ServersTable.currentLoadPercent]}%")
        }

        // 5. Policy + rules
        val policyId = PoliciesTable.insertAndGetId {
            it[PoliciesTable.userId] = userId
            it[name] = "My Profile"
            it[defaultMode] = "VPN_ALL"
            it[isActive] = true
        }.value

        AppRulesTable.insert {
            it[AppRulesTable.policyId] = policyId
            it[appIdentifier] = "com.netflix"
            it[appName] = "Netflix"
            it[platform] = "IOS"
            it[action] = "VPN"
        }
        AppRulesTable.insert {
            it[AppRulesTable.policyId] = policyId
            it[appIdentifier] = "com.slack"
            it[appName] = "Slack"
            it[platform] = "IOS"
            it[action] = "DIRECT"
        }

        DomainRulesTable.insert {
            it[DomainRulesTable.policyId] = policyId
            it[domainPattern] = "*.google.com"
            it[action] = "DIRECT"
        }

        println("📜 Policy: $policyId")
        println("   Apps:")
        AppRulesTable.selectAll().forEach {
            println("      ${it[AppRulesTable.appName]} → ${it[AppRulesTable.action]}")
        }
        println("   Domains:")
        DomainRulesTable.selectAll().forEach {
            println("      ${it[DomainRulesTable.domainPattern]} → ${it[DomainRulesTable.action]}")
        }

        // 6. Key rotation
        KeyRotationTable.insert {
            it[KeyRotationTable.deviceId] = deviceId
            it[publicKey] = "NEW_KEY_V1"
            it[keyVersion] = 1
            it[status] = "ACTIVE"
            it[rotationReason] = "SCHEDULED"
            it[expiresAt] = Instant.now().plus(90, ChronoUnit.DAYS)
        }
        println("🔑 Key created")

        // 7. Plan + subscription
        val planId = PlansTable.insertAndGetId {
            it[code] = "PREMIUM"
            it[name] = "Premium"
            it[priceMonthly] = BigDecimal("9.99")
            it[priceYearly] = BigDecimal("99.99")
            it[maxDevices] = 5
            it[features] = """["split_tunnel","kill_switch"]"""
        }.value

        SubscriptionsTable.insert {
            it[SubscriptionsTable.userId] = userId
            it[SubscriptionsTable.planId] = planId
            it[billingPeriod] = "YEARLY"
            it[startDate] = Instant.now()
            it[endDate] = Instant.now().plus(365, ChronoUnit.DAYS)
        }
        println("💳 Premium subscription activated")

        // 8. Audit
        listOf("LOGIN_SUCCESS", "DEVICE_REGISTER", "VPN_CONNECT").forEach { act ->
            AuditLogsTable.insert {
                it[AuditLogsTable.userId] = userId
                it[actorType] = "USER"
                it[actorIp] = "192.168.1.1"
                it[action] = act
                it[status] = "SUCCESS"
            }
        }
        println("📝 Audit: ${AuditLogsTable.selectAll().count()} records")

        // 9. Metrics
        repeat(5) {
            MetricsTable.insert {
                it[MetricsTable.userId] = userId
                it[MetricsTable.deviceId] = deviceId
                it[metricType] = "BANDWIDTH_DOWN"
                it[value] = (100_000_000L..500_000_000L).random()
                it[unit] = "BYTES"
                it[timestamp] = Instant.now()
            }
        }

        // Traffic summary - simple version
        val metricsCount = MetricsTable.selectAll().count()
        println("📊 Metrics recorded: $metricsCount")

        // Summary
        println("\n=== SUMMARY ===")
        println("Users: ${UsersTable.selectAll().count()}")
        println("Devices: ${DevicesTable.selectAll().count()}")
        println("Servers: ${ServersTable.selectAll().count()}")
        println("Policies: ${PoliciesTable.selectAll().count()}")
    }

    println("\n✅ Done!")
}