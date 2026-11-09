package app.chspch.client.vpn

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import app.chspch.client.MainActivity

/** Uses the app connection/permission flow, without retaining VPN credentials. */
class VpnTileService : TileService() {
    private var listening = false
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateTileState()
    }
    override fun onStartListening() {
        super.onStartListening()
        if (!listening) {
            ContextCompat.registerReceiver(this, stateReceiver,
                IntentFilter(SingBoxVpnService.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            listening = true
        }
        updateTileState()
    }
    override fun onStopListening() {
        if (listening) unregisterReceiver(stateReceiver)
        listening = false
        super.onStopListening()
    }
    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { toggle() } else toggle()
    }
    private fun toggle() {
        val state = SingBoxVpnService.runtimeState(this).state
        if (state in setOf(SingBoxVpnService.STATE_ON, SingBoxVpnService.STATE_PAUSED,
                SingBoxVpnService.STATE_CONNECTING)) {
            SingBoxTunnel(this).stop()
        } else {
            val intent = Intent(this, MainActivity::class.java).setAction(ACTION_CONNECT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 7401, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }
    private fun updateTileState() {
        val snapshot = SingBoxVpnService.runtimeState(this)
        qsTile?.apply {
            label = "ЧСПЧ"
            state = if (snapshot.state in setOf(SingBoxVpnService.STATE_ON,
                    SingBoxVpnService.STATE_PAUSED, SingBoxVpnService.STATE_CONNECTING))
                Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) subtitle = when (snapshot.state) {
                SingBoxVpnService.STATE_ON -> "Подключён"
                SingBoxVpnService.STATE_CONNECTING -> "Подключение…"
                SingBoxVpnService.STATE_PAUSED -> "Пауза"
                else -> "Выключен"
            }
            updateTile()
        }
    }
    companion object {
        const val ACTION_CONNECT = "app.chspch.client.action.CONNECT_VPN"
    }
}
