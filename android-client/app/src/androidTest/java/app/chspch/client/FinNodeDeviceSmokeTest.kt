package app.chspch.client

import android.net.VpnService
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.chspch.client.data.BackendApi
import app.chspch.client.data.RouteRule
import app.chspch.client.vpn.SingBoxTunnel
import app.chspch.client.vpn.SingBoxVpnService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Opt-in live acceptance check. Requires a guest session and an already approved Android VPN permission. */
@RunWith(AndroidJUnit4::class)
class FinNodeDeviceSmokeTest {
    @Test fun guestTelegramAndDirectTraffic() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("finNodeSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = BackendApi(context)
        assumeTrue("Keep the user's Telegram account intact", api.telegramAccount() == null)
        assumeTrue("Approve Android VPN permission in the app first", VpnService.prepare(context) == null)
        val policy = api.loadRoutingPolicy()
        assertEquals("DIRECT", policy.defaultRoute)
        assertTrue(policy.routeRules.isNotEmpty())
        assertTrue(policy.routeRules.all { it.defaultRuleKey in setOf("vpn-telegram-domains", "vpn-telegram-ip") })
        // Rotate the guest connection so updated node metadata takes effect; authentication is preserved.
        val issued = api.issueVpnConfig(
            exitNodeId = "00000000-0000-0000-0000-000000000001", routeMode = "CASCADE", forceRotate = true
        )
        assertEquals("SINGLE", issued.routeMode)
        val config = requireNotNull(issued.clientConfigJson)
        assertEquals(446, org.json.JSONObject(config).getJSONArray("outbounds").getJSONObject(0).getInt("server_port"))
        val tunnel = SingBoxTunnel(context)
        suspend fun start(candidate: app.chspch.client.data.RoutingPolicy) {
            val requestedAt = System.currentTimeMillis()
            tunnel.startWithClientConfig(config, candidate)
            val until = SystemClock.elapsedRealtime() + 45_000
            while (SystemClock.elapsedRealtime() < until) {
                val snapshot = SingBoxVpnService.runtimeState(context)
                if (snapshot.updatedAt >= requestedAt && snapshot.state == SingBoxVpnService.STATE_CONFIG_FAILED) error("VPN configuration failed")
                if (snapshot.updatedAt >= requestedAt && snapshot.state == SingBoxVpnService.STATE_ON) {
                    SystemClock.sleep(1500)
                    return
                }
                SystemClock.sleep(250)
            }
            error("VPN start timed out")
        }
        fun get(url: String): Pair<Int, String> {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            return try {
                val code = connection.responseCode
                code to if (code in 200..299) connection.inputStream.bufferedReader().use { it.readText().take(512) } else ""
            } finally { connection.disconnect() }
        }
        start(policy)
        try {
            val telegram = get("https://api.telegram.org")
            assertTrue("Telegram must respond through guest VPN", telegram.first in 200..399)
            val direct = get("https://api.ipify.org")
            assertEquals(200, direct.first)
            assertTrue("Unselected service must use the phone's direct egress", direct.second.trim() != "157.228.178.210")
            println("FIN_SMOKE: Telegram HTTP ${telegram.first}; direct egress differs from fin")
            // Deliberately proxy another service to prove the restriction exists on the node, not just in the UI.
            val invalid = policy.copy(routeRules = policy.routeRules + RouteRule(
                "device-smoke-blocked", "USER", null, "Device negative check", "", true, 900,
                "DOMAIN_SUFFIX", listOf("example.com"), "VPN"
            ))
            start(invalid)
            val blocked = try { get("https://example.com").first !in 200..399 } catch (_: IOException) { true }
            assertTrue("Guest node must refuse an unrelated website even with a modified client rule", blocked)
            println("FIN_SMOKE: unrelated proxied website refused")
        } finally {
            // Restore the real server policy, leaving the phone with the intended configuration.
            start(policy)
        }
    }
}
