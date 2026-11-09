package app.chspch.client.update

import android.app.Notification
import android.app.NotificationManager
import android.app.DownloadManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: downloads the published update, verifies it, but never installs or changes login. */
@RunWith(AndroidJUnit4::class)
class UpdateDeviceSmokeTest {
    @Test fun publicUpdateWithoutVpnAndBackgroundDownload() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("updateSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val network = context.getSystemService(ConnectivityManager::class.java)
        assertFalse("Public updater must be tested without VPN", network.getNetworkCapabilities(network.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.cancel(48102)
        val updater = AppUpdater(context)
        val update = requireNotNull(updater.check()) { "Publish a version newer than the installed bootstrap first" }
        updater.notifyUpdate(update)
        updater.download(update)
        val deadline = System.currentTimeMillis() + 180_000
        while (updater.downloadStatus() != DownloadManager.STATUS_SUCCESSFUL && System.currentTimeMillis() < deadline) {
            assertNotEquals(DownloadManager.STATUS_FAILED, updater.downloadStatus())
            delay(1000)
        }
        assertEquals(DownloadManager.STATUS_SUCCESSFUL, updater.downloadStatus())
        val apk = updater.verifiedApk(update)
        assertEquals(update.sizeBytes, apk.length())
        var ready = false
        repeat(60) {
            if (notifications.activeNotifications.any { it.notification.extras.getString(Notification.EXTRA_TITLE) == "Обновление ЧСПЧ готово" }) ready = true
            if (!ready) delay(1000)
        }
        assertTrue("Background download must produce a verified ready notification", ready)
        println("UPDATE_SMOKE: public HTTPS without VPN; download, checksum, package, version, signature and ready notification OK")
    }
}
