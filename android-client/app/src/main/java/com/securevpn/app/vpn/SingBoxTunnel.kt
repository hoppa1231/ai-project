package com.securevpn.app.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.securevpn.app.data.RoutingPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SingBoxTunnel(context: Context) {
    private val appContext = context.applicationContext

    suspend fun start(
        vlessUri: String,
        policy: RoutingPolicy = RoutingPolicy.default(),
        quotaUsedBytes: Long = 0L,
        quotaTotalBytes: Long = 0L
    ) = withContext(Dispatchers.IO) {
        val config = SingBoxConfigFactory.fromVlessUri(vlessUri, policy)
        val intent = Intent(appContext, SingBoxVpnService::class.java)
            .setAction(SingBoxVpnService.ACTION_START)
            .putExtra(SingBoxVpnService.EXTRA_CONFIG, config)
            .putExtra(SingBoxVpnService.EXTRA_QUOTA_USED_BYTES, quotaUsedBytes)
            .putExtra(SingBoxVpnService.EXTRA_QUOTA_TOTAL_BYTES, quotaTotalBytes)
        ContextCompat.startForegroundService(appContext, intent)
    }

    fun stop() {
        val intent = Intent(appContext, SingBoxVpnService::class.java)
            .setAction(SingBoxVpnService.ACTION_STOP)
        appContext.startService(intent)
    }
}
