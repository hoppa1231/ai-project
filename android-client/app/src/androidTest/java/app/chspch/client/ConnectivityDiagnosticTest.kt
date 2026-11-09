package app.chspch.client

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.chspch.client.data.BackendApi
import app.chspch.client.vpn.SingBoxTunnel
import app.chspch.client.vpn.SingBoxVpnService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/** Opt-in live check: validates credentials with the server and leaves the real VPN running.
 * Instrumentation restarts the app process, so an explicit tunnel start is necessary.
 * Authentication, user rules and selected node are preserved; no credentials are logged.
 */
@RunWith(AndroidJUnit4::class)
class ConnectivityDiagnosticTest {
    @Test fun revalidateConfigAndVerifyTelegram() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("connectivityDiagnostic") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = BackendApi(context)
        val node = context.getSharedPreferences("securevpn_api", 0).getString("active_node_id", null)
        val issued = api.issueVpnConfig(exitNodeId = node)
        val config = requireNotNull(issued.clientConfigJson)
        val requestedAt = System.currentTimeMillis()
        SingBoxTunnel(context).startWithClientConfig(config, api.cachedRoutingPolicy())
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = SingBoxVpnService.runtimeState(context)
            check(state.state != SingBoxVpnService.STATE_CONFIG_FAILED)
            if (state.state == SingBoxVpnService.STATE_ON && state.updatedAt >= requestedAt) break
            SystemClock.sleep(250)
        }
        assertTrue(SingBoxVpnService.runtimeState(context).state == SingBoxVpnService.STATE_ON)
        for (endpoint in listOf("https://telegram.org", "https://api.telegram.org", "https://example.com")) {
            val started = SystemClock.elapsedRealtime()
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.requestMethod = "HEAD"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            try {
                val code = connection.responseCode
                println("CONNECTIVITY_SMOKE $endpoint HTTP=$code ms=${SystemClock.elapsedRealtime() - started}")
                assertTrue("$endpoint must respond", code in 200..399)
            } finally { connection.disconnect() }
        }
    }
}
