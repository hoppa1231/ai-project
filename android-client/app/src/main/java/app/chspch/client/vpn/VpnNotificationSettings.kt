package app.chspch.client.vpn

import android.content.Context

object VpnNotificationSettings {
    const val PREFS = "vpn_notification_settings"
    const val KEY_SHOW_TRAFFIC = "show_traffic"

    fun showTraffic(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_TRAFFIC, true)

    fun setShowTraffic(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SHOW_TRAFFIC, enabled).apply()
    }
}
