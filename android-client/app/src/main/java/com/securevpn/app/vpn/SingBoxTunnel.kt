package com.securevpn.app.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SingBoxTunnel(context: Context) {
    private val appContext = context.applicationContext

    suspend fun start(vlessUri: String) = withContext(Dispatchers.IO) {
        val config = SingBoxConfigFactory.fromVlessUri(vlessUri)
        val intent = Intent(appContext, SingBoxVpnService::class.java)
            .setAction(SingBoxVpnService.ACTION_START)
            .putExtra(SingBoxVpnService.EXTRA_CONFIG, config)
        ContextCompat.startForegroundService(appContext, intent)
    }

    fun stop() {
        val intent = Intent(appContext, SingBoxVpnService::class.java)
            .setAction(SingBoxVpnService.ACTION_STOP)
        appContext.startService(intent)
    }
}

