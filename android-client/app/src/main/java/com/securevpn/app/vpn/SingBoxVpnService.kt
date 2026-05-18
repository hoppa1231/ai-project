package com.securevpn.app.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.RemoteViews
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.TrafficStats
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.app.NotificationCompat
import com.securevpn.app.R
import com.securevpn.app.MainActivity
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Connection
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.ServiceStatusMessage
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import io.nekohasekai.libbox.NetworkInterface as BoxNetworkInterface

class SingBoxVpnService : VpnService(), PlatformInterface, CommandServerHandler, CommandClientHandler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var commandServer: CommandServer? = null
    private var commandClient: CommandClient? = null
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var currentConfig: String? = null
    private var defaultInterfaceListener: InterfaceUpdateListener? = null
    private var defaultNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var trafficSamplerJob: Job? = null
    private var lastUidTrafficBytes: Long = TrafficStats.UNSUPPORTED.toLong()
    private var quotaUsedBaseBytes: Long = 0L
    private var quotaTotalBytes: Long = 0L
    private var initialized = false
    @Volatile private var paused = false
    @Volatile private var notificationText = "Ожидание..."
    @Volatile private var lastNotificationUpdate = 0L
    private val connectionOutbounds = ConcurrentHashMap<String, String>()
    private val traffic = TrafficCounters()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG).orEmpty()
                quotaUsedBaseBytes = intent.getLongExtra(EXTRA_QUOTA_USED_BYTES, 0L).coerceAtLeast(0L)
                quotaTotalBytes = intent.getLongExtra(EXTRA_QUOTA_TOTAL_BYTES, 0L).coerceAtLeast(0L)
                paused = false
                traffic.reset()
                connectionOutbounds.clear()
                startForeground(NOTIFICATION_ID, buildNotification("Подключение..."))
                publishState(STATE_CONNECTING)
                scope.launch {
                    runCatching { startCore(config) }
                        .onFailure { error ->
                            startForeground(
                                NOTIFICATION_ID,
                                buildNotification(error.message?.take(90) ?: "Ошибка запуска VPN")
                            )
                            publishState(STATE_CONFIG_FAILED)
                            closeCoreResources()
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                    }
                }
            }
            ACTION_PAUSE -> {
                paused = true
                runCatching { commandServer?.pause() }
                publishState(STATE_PAUSED)
                refreshNotification("VPN на паузе")
            }
            ACTION_RESUME -> {
                paused = false
                runCatching { commandServer?.wake() }
                publishState(STATE_ON)
                refreshNotification("VPN работает")
            }
            ACTION_STOP -> scope.launch { stopCore() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCore()
        scope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        scope.launch { stopCore() }
        super.onRevoke()
    }

    private fun startCore(config: String) {
        require(config.isNotBlank()) { "sing-box config is empty" }
        if (prepare(this) != null) error("Android VPN permission is missing")
        initializeLibbox()
        currentConfig = config
        startDefaultNetworkMonitor()

        val server = commandServer ?: CommandServer(this, this).also {
            it.start()
            commandServer = it
        }
        server.checkConfig(config)
        server.startOrReloadService(config, OverrideOptions().apply {
            autoRedirect = false
        })
        startCommandClient()
        startTrafficSampler()
        publishState(STATE_ON)
        refreshNotification("VPN работает")
    }

    private fun stopCore() {
        publishState(STATE_OFF)
        closeCoreResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeCoreResources() {
        runCatching { commandServer?.closeService() }
        runCatching { commandClient?.disconnect() }
        commandClient = null
        trafficSamplerJob?.cancel()
        trafficSamplerJob = null
        lastUidTrafficBytes = TrafficStats.UNSUPPORTED.toLong()
        runCatching { commandServer?.close() }
        commandServer = null
        stopDefaultNetworkMonitor()
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null
        currentConfig = null
        paused = false
        traffic.reset()
        connectionOutbounds.clear()
    }

    private fun initializeLibbox() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val baseDir = filesDir.apply { mkdirs() }
            val workDir = getExternalFilesDir(null)?.apply { mkdirs() } ?: baseDir
            val tempDir = cacheDir.apply { mkdirs() }
            val stderr = File(workDir, "sing-box-stderr.log").apply {
                if (exists()) delete()
                createNewFile()
            }

            Libbox.setup(SetupOptions().apply {
                basePath = baseDir.path
                workingPath = workDir.path
                tempPath = tempDir.path
                fixAndroidStack = false
                logMaxLines = 300
            })
            Libbox.redirectStderr(stderr.path)
            Libbox.setMemoryLimit(true)
            initialized = true
        }
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("Android VPN permission is missing")
        val builder = Builder()
            .setSession("SecureVPN")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        addAddresses(builder, options.inet4Address)
        addAddresses(builder, options.inet6Address)

        if (options.autoRoute) {
            var hasDnsServer = false
            options.dnsServerAddress?.value?.takeIf { it.isNotBlank() }?.let { dnsServer ->
                builder.addDnsServer(dnsServer)
                hasDnsServer = true
            }
            if (!hasDnsServer) {
                builder.addDnsServer(FALLBACK_TUN_DNS)
            }
            addRoutes(builder, options.inet4RouteAddress, fallback = "0.0.0.0/0")
            addRoutes(builder, options.inet6RouteAddress)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                excludeRoutes(builder, options.inet4RouteExcludeAddress)
                excludeRoutes(builder, options.inet6RouteExcludeAddress)
            }
            applyPackageRules(builder, options.includePackage, include = true)
            applyPackageRules(builder, options.excludePackage, include = false)
        }

        if (options.isHTTPProxyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setHttpProxy(
                ProxyInfo.buildDirectProxy(
                    options.httpProxyServer,
                    options.httpProxyServerPort,
                    options.httpProxyBypassDomain.toList()
                )
            )
        }

        val pfd = builder.establish() ?: error("Failed to establish Android VPN")
        tunDescriptor = pfd
        return pfd.fd
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() = Unit
    override fun localDNSTransport() = null

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        defaultInterfaceListener = listener
        notifyDefaultInterface(defaultNetwork ?: activeNetwork())
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        if (defaultInterfaceListener == listener) defaultInterfaceListener = null
    }

    override fun readWIFIState(): WIFIState? {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager ?: return null
        @Suppress("DEPRECATION")
        val info = wifiManager.connectionInfo ?: return null
        val ssid = info.ssid?.trim('"') ?: ""
        return Libbox.newWIFIState(ssid, info.bssid ?: "")
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return BoxNetworkInterfaces(emptyList())
        val javaInterfaces = java.net.NetworkInterface.getNetworkInterfaces().toList()
        val interfaces = connectivity.allNetworks.mapNotNull { network ->
            val link = connectivity.getLinkProperties(network) ?: return@mapNotNull null
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            val name = link.interfaceName ?: return@mapNotNull null
            val javaInterface = javaInterfaces.firstOrNull { it.name == name } ?: return@mapNotNull null
            BoxNetworkInterface().apply {
                this.name = name
                index = javaInterface.index
                mtu = runCatching { javaInterface.mtu }.getOrDefault(1500)
                dnsServer = link.dnsServers.mapNotNull { it.hostAddress }.toStringIterator()
                addresses = javaInterface.interfaceAddresses.map { address ->
                    val host = if (address.address is Inet6Address) {
                        Inet6Address.getByAddress(address.address.address).hostAddress
                    } else {
                        address.address.hostAddress
                    }
                    "$host/${address.networkPrefixLength}"
                }.toStringIterator()
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                flags = 0x1 or 0x40
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
        return BoxNetworkInterfaces(interfaces)
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
        val owner = ConnectionOwner()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val connectivity = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
            val uid = runCatching {
                connectivity?.getConnectionOwnerUid(
                    ipProtocol,
                    InetSocketAddress(sourceAddress, sourcePort),
                    InetSocketAddress(destinationAddress, destinationPort)
                )
            }.getOrNull() ?: Process.INVALID_UID
            owner.userId = uid
            owner.androidPackageName = packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
        } else {
            owner.userId = -1
            owner.androidPackageName = ""
        }
        return owner
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun systemCertificates(): StringIterator {
        val keyStore = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
        val certs = keyStore.aliases().toList().mapNotNull { alias ->
            keyStore.getCertificate(alias)?.encoded?.let { data ->
                "-----BEGIN CERTIFICATE-----\n${Base64.encode(data)}\n-----END CERTIFICATE-----"
            }
        }
        return certs.toStringIterator()
    }

    override fun sendNotification(notification: Notification) = Unit

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }

    override fun setSystemProxyEnabled(enabled: Boolean) = Unit

    override fun serviceReload() {
        currentConfig?.let { config -> scope.launch { startCore(config) } }
    }

    override fun serviceStop() {
        scope.launch { stopCore() }
    }

    override fun writeDebugMessage(message: String) = Unit

    override fun connected() = Unit

    override fun disconnected(message: String) = Unit

    override fun clearLogs() = Unit

    override fun setDefaultLogLevel(level: Int) = Unit

    override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit

    override fun updateClashMode(newMode: String) = Unit

    override fun writeLogs(messageList: LogIterator) = Unit

    override fun writeGroups(message: OutboundGroupIterator) = Unit

    override fun writeServiceStatus(message: ServiceStatusMessage) = Unit

    override fun writeStatus(message: StatusMessage) {
        if (!message.getTrafficAvailable()) return
        traffic.setTotal(message.getUplinkTotal() + message.getDownlinkTotal())
        publishState(if (paused) STATE_PAUSED else STATE_ON)
        refreshNotification(if (paused) "VPN на паузе" else "VPN работает", throttle = true)
    }

    override fun writeConnectionEvents(message: ConnectionEvents) {
        if (message.getReset()) {
            connectionOutbounds.clear()
        }
        val iterator = message.iterator()
        while (iterator.hasNext()) {
            val event = iterator.next()
            val connection = event.getConnection()
            val outbound = connection?.normalizedOutbound()
                ?: connectionOutbounds[event.getID()]
                ?: "proxy"
            if (connection != null && event.getID().isNotBlank()) {
                connectionOutbounds[event.getID()] = outbound
            }
            val bytes = event.getUplinkDelta() + event.getDownlinkDelta()
            if (bytes > 0) traffic.add(outbound, bytes)
            if (event.getType().toLong() == Libbox.ConnectionEventClosed) {
                connectionOutbounds.remove(event.getID())
            }
        }
        publishState(if (paused) STATE_PAUSED else STATE_ON)
        refreshNotification(if (paused) "VPN на паузе" else "VPN работает", throttle = true)
    }

    private fun startCommandClient() {
        if (commandClient != null) return
        runCatching {
            val options = CommandClientOptions().apply {
                setStatusInterval(1_000_000_000L)
                addCommand(Libbox.CommandStatus)
                addCommand(Libbox.CommandConnections)
            }
            Libbox.newCommandClient(this, options).also { client ->
                commandClient = client
                client.connect()
            }
        }
    }

    private fun startTrafficSampler() {
        if (trafficSamplerJob != null) return
        lastUidTrafficBytes = uidTrafficBytes()
        trafficSamplerJob = scope.launch {
            while (true) {
                delay(1000)
                val current = uidTrafficBytes()
                val previous = lastUidTrafficBytes
                if (current != TrafficStats.UNSUPPORTED.toLong() && previous != TrafficStats.UNSUPPORTED.toLong()) {
                    val delta = current - previous
                    if (delta > 0) {
                        traffic.addFallbackProxy(delta)
                        publishState(if (paused) STATE_PAUSED else STATE_ON)
                        refreshNotification(if (paused) "VPN на паузе" else "VPN работает", throttle = true)
                    }
                }
                lastUidTrafficBytes = current
            }
        }
    }

    private fun uidTrafficBytes(): Long {
        val uid = applicationInfo.uid
        val tx = TrafficStats.getUidTxBytes(uid)
        val rx = TrafficStats.getUidRxBytes(uid)
        return if (tx == TrafficStats.UNSUPPORTED.toLong() || rx == TrafficStats.UNSUPPORTED.toLong()) {
            TrafficStats.UNSUPPORTED.toLong()
        } else {
            tx + rx
        }
    }

    private fun startDefaultNetworkMonitor() {
        val connectivity = connectivityManager() ?: return
        if (networkCallback != null) return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network.isPhysicalInternetNetwork()) {
                    defaultNetwork = network
                    notifyDefaultInterface(network)
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (networkCapabilities.isPhysicalInternetCapabilities()) {
                    defaultNetwork = network
                    notifyDefaultInterface(network)
                } else if (defaultNetwork == network) {
                    defaultNetwork = physicalInternetNetwork()
                    notifyDefaultInterface(defaultNetwork)
                }
            }

            override fun onLost(network: Network) {
                if (defaultNetwork == network) {
                    defaultNetwork = physicalInternetNetwork()
                    notifyDefaultInterface(defaultNetwork)
                }
            }
        }
        networkCallback = callback
        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    connectivity.registerBestMatchingNetworkCallback(
                        request,
                        callback,
                        Handler(Looper.getMainLooper())
                    )
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                    connectivity.requestNetwork(request, callback, Handler(Looper.getMainLooper()))
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> {
                    connectivity.registerDefaultNetworkCallback(callback)
                }
                else -> connectivity.requestNetwork(request, callback)
            }
        }.onFailure {
            networkCallback = null
            physicalInternetNetwork()?.let {
                defaultNetwork = it
                notifyDefaultInterface(it)
            }
        }
    }

    private fun stopDefaultNetworkMonitor() {
        val callback = networkCallback ?: return
        runCatching { connectivityManager()?.unregisterNetworkCallback(callback) }
        networkCallback = null
        defaultNetwork = null
        defaultInterfaceListener?.updateDefaultInterface("", -1, false, false)
    }

    private fun notifyDefaultInterface(network: Network?) {
        val listener = defaultInterfaceListener ?: return
        val connectivity = connectivityManager() ?: return
        val targetNetwork = network?.takeIf { it.isPhysicalInternetNetwork() } ?: physicalInternetNetwork()
        val link = network?.let { connectivity.getLinkProperties(it) }
        val targetLink = targetNetwork?.let { connectivity.getLinkProperties(it) }
        val interfaceName = targetLink?.interfaceName ?: link?.interfaceName
        if (interfaceName.isNullOrBlank()) {
            listener.updateDefaultInterface("", -1, false, false)
            return
        }
        val index = runCatching { java.net.NetworkInterface.getByName(interfaceName).index }
            .getOrDefault(-1)
        val capabilities = targetNetwork?.let { connectivity.getNetworkCapabilities(it) }
        listener.updateDefaultInterface(
            interfaceName,
            index,
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) == false
        )
    }

    private fun activeNetwork(): Network? {
        val connectivity = connectivityManager() ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivity.activeNetwork?.takeIf { it.isPhysicalInternetNetwork() } ?: physicalInternetNetwork()
        } else {
            physicalInternetNetwork()
        }
    }

    private fun physicalInternetNetwork(): Network? {
        val connectivity = connectivityManager() ?: return null
        return connectivity.allNetworks.firstOrNull { it.isPhysicalInternetNetwork() }
    }

    private fun Network.isPhysicalInternetNetwork(): Boolean {
        val capabilities = connectivityManager()?.getNetworkCapabilities(this) ?: return false
        return capabilities.isPhysicalInternetCapabilities()
    }

    private fun NetworkCapabilities.isPhysicalInternetCapabilities(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            !hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    private fun connectivityManager(): ConnectivityManager? =
        getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager

    private fun refreshNotification(text: String, throttle: Boolean = false) {
        val now = System.currentTimeMillis()
        if (throttle && now - lastNotificationUpdate < 950L) return
        notificationText = text
        lastNotificationUpdate = now
        publishState(if (paused) STATE_PAUSED else STATE_ON)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun publishState(state: String) {
        val currentBytes = quotaUsedBaseBytes + traffic.total()
        val line = quotaDisplayLine(currentBytes, quotaTotalBytes)
        statePrefs()
            .edit()
            .putString(KEY_STATE, state)
            .putString(KEY_TRAFFIC, line)
            .putLong(KEY_TRAFFIC_BYTES, currentBytes)
            .putLong(KEY_QUOTA_TOTAL_BYTES, quotaTotalBytes)
            .apply()
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_TRAFFIC, line)
                .putExtra(EXTRA_TRAFFIC_BYTES, currentBytes)
                .putExtra(EXTRA_QUOTA_TOTAL_BYTES, quotaTotalBytes)
        )
    }

    private fun statePrefs(): SharedPreferences =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun buildNotification(text: String): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN управление", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Статус VPN, пауза, остановка и счетчики трафика"
                    setShowBadge(false)
                }
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseAction = if (paused) ACTION_RESUME else ACTION_PAUSE
        val pauseTitle = if (paused) "Продолжить" else "Пауза"
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SingBoxVpnService::class.java).setAction(pauseAction),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, SingBoxVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val currentBytes = quotaUsedBaseBytes + traffic.total()
        val trafficLine = quotaDisplayLine(currentBytes, quotaTotalBytes)
        val progress = quotaProgress(currentBytes, quotaTotalBytes)
        val title = if (paused) "ВПН на паузе" else "ВПН включен"
        val icon = if (paused) R.drawable.ic_notification_play else R.drawable.ic_notification_power
        val customView = RemoteViews(packageName, R.layout.notification_vpn).apply {
            setImageViewResource(R.id.notificationStateIcon, icon)
            setTextViewText(R.id.notificationTitle, title)
            setTextViewText(R.id.notificationStatus, text)
            setTextViewText(R.id.notificationTraffic, trafficLine)
            setProgressBar(R.id.notificationTrafficProgress, 1000, progress, false)
            setTextViewText(R.id.notificationPause, pauseTitle.uppercase())
            setTextViewText(R.id.notificationStop, "ОСТАНОВИТЬ")
            setOnClickPendingIntent(R.id.notificationPause, pauseIntent)
            setOnClickPendingIntent(R.id.notificationStop, stopIntent)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(trafficLine)
            .setContentIntent(pendingIntent)
            .setCustomContentView(customView)
            .setCustomBigContentView(customView)
            .setCustomHeadsUpContentView(customView)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(0xFF2A150A.toInt())
            .setColorized(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun Connection.normalizedOutbound(): String {
        val outbound = getOutbound().ifBlank { getFromOutbound() }.lowercase()
        return if (outbound == "direct") "direct" else "proxy"
    }

    private fun quotaDisplayLine(currentBytes: Long, totalBytes: Long): String {
        return if (totalBytes <= 0L) {
            "${formatBytes(currentBytes)} / ∞"
        } else {
            "${formatBytes(currentBytes)} / ${formatBytes(totalBytes)}"
        }
    }

    private fun quotaProgress(currentBytes: Long, totalBytes: Long): Int {
        return if (totalBytes <= 0L) {
            1000
        } else {
            ((currentBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0) * 1000.0).toInt()
        }
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var value = bytes.coerceAtLeast(0L).toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) {
            "${bytes.coerceAtLeast(0L)} ${units[unit]}"
        } else {
            String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
        }
    }

    private fun addAddresses(builder: Builder, iterator: io.nekohasekai.libbox.RoutePrefixIterator) {
        iterator.toPrefixStrings().forEach { prefix ->
            val (address, length) = splitPrefix(prefix)
            builder.addAddress(address, length)
        }
    }

    private fun addRoutes(
        builder: Builder,
        iterator: io.nekohasekai.libbox.RoutePrefixIterator,
        fallback: String? = null
    ) {
        val routes = iterator.toPrefixStrings().ifEmpty { fallback?.let(::listOf).orEmpty() }
        routes.forEach { prefix ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val (address, length) = splitPrefix(prefix)
                builder.addRoute(IpPrefix(InetAddress.getByName(address), length))
            } else {
                val (address, length) = splitPrefix(prefix)
                builder.addRoute(address, length)
            }
        }
    }

    private fun excludeRoutes(builder: Builder, iterator: io.nekohasekai.libbox.RoutePrefixIterator) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        iterator.toPrefixStrings().forEach { prefix ->
            val (address, length) = splitPrefix(prefix)
            builder.excludeRoute(IpPrefix(InetAddress.getByName(address), length))
        }
    }

    private fun applyPackageRules(builder: Builder, iterator: StringIterator, include: Boolean) {
        iterator.toList().forEach { packageName ->
            runCatching {
                if (include) builder.addAllowedApplication(packageName) else builder.addDisallowedApplication(packageName)
            }
        }
    }

    private fun io.nekohasekai.libbox.RoutePrefixIterator.toPrefixStrings(): List<String> {
        val items = mutableListOf<String>()
        while (hasNext()) items += next().string()
        return items
    }

    private fun StringIterator.toList(): List<String> {
        val items = mutableListOf<String>()
        while (hasNext()) items += next()
        return items
    }

    private fun splitPrefix(prefix: String): Pair<String, Int> {
        val parts = prefix.substringBefore('%').split('/', limit = 2)
        return parts[0] to parts.getOrNull(1)?.toIntOrNull().orDefaultPrefix(parts[0])
    }

    private fun Int?.orDefaultPrefix(address: String): Int = this ?: if (address.contains(':')) 128 else 32

    private fun List<String>.toStringIterator(): StringIterator = BoxStringIterator(this)

    private class BoxStringIterator(private val items: List<String>) : StringIterator {
        private val iterator = items.iterator()
        override fun len(): Int = items.size
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
    }

    private class BoxNetworkInterfaces(private val interfaces: List<BoxNetworkInterface>) : NetworkInterfaceIterator {
        private val iterator = interfaces.iterator()
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): BoxNetworkInterface = iterator.next()
    }

    companion object {
        const val ACTION_START = "com.securevpn.app.vpn.START"
        const val ACTION_PAUSE = "com.securevpn.app.vpn.PAUSE"
        const val ACTION_RESUME = "com.securevpn.app.vpn.RESUME"
        const val ACTION_STOP = "com.securevpn.app.vpn.STOP"
        const val ACTION_STATE_CHANGED = "com.securevpn.app.vpn.STATE_CHANGED"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_STATE = "state"
        const val EXTRA_TRAFFIC = "traffic"
        const val EXTRA_TRAFFIC_BYTES = "traffic_bytes"
        const val EXTRA_QUOTA_USED_BYTES = "quota_used_bytes"
        const val EXTRA_QUOTA_TOTAL_BYTES = "quota_total_bytes"
        const val STATE_OFF = "OFF"
        const val STATE_CONNECTING = "CONNECTING"
        const val STATE_PAUSED = "PAUSED"
        const val STATE_ON = "ON"
        const val STATE_CONFIG_FAILED = "CONFIG_FAILED"
        private const val CHANNEL_ID = "securevpn_control"
        private const val NOTIFICATION_ID = 42
        private const val FALLBACK_TUN_DNS = "172.19.0.2"
        private const val PREFS_NAME = "vpn-runtime-state"
        private const val KEY_STATE = "state"
        private const val KEY_TRAFFIC = "traffic"
        private const val KEY_TRAFFIC_BYTES = "traffic_bytes"
        private const val KEY_QUOTA_TOTAL_BYTES = "quota_total_bytes"

        fun runtimeState(context: Context): RuntimeSnapshot {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return RuntimeSnapshot(
                state = prefs.getString(KEY_STATE, STATE_OFF) ?: STATE_OFF,
                trafficText = prefs.getString(KEY_TRAFFIC, "") ?: "",
                trafficBytes = prefs.getLong(KEY_TRAFFIC_BYTES, 0L),
                quotaTotalBytes = prefs.getLong(KEY_QUOTA_TOTAL_BYTES, 0L)
            )
        }
    }

    data class RuntimeSnapshot(
        val state: String,
        val trafficText: String,
        val trafficBytes: Long,
        val quotaTotalBytes: Long
    )

    private class TrafficCounters {
        @Volatile private var directBytes: Long = 0
        @Volatile private var proxyBytes: Long = 0
        @Volatile private var totalBytes: Long = 0

        @Synchronized
        fun add(outbound: String, bytes: Long) {
            if (outbound == "direct") directBytes += bytes else proxyBytes += bytes
        }

        @Synchronized
        fun addFallbackProxy(bytes: Long) {
            val classified = directBytes + proxyBytes
            if (classified < totalBytes) return
            proxyBytes += bytes
        }

        @Synchronized
        fun setTotal(bytes: Long) {
            totalBytes = bytes
        }

        @Synchronized
        fun reset() {
            directBytes = 0
            proxyBytes = 0
            totalBytes = 0
        }

        @Synchronized
        fun displayLine(): String {
            return formatBytes(total())
        }

        @Synchronized
        fun total(): Long {
            val measured = directBytes + proxyBytes
            return maxOf(totalBytes, measured)
        }

        private fun formatBytes(bytes: Long): String {
            val units = arrayOf("Б", "КБ", "МБ", "ГБ")
            var value = bytes.toDouble()
            var unit = 0
            while (value >= 1024.0 && unit < units.lastIndex) {
                value /= 1024.0
                unit++
            }
            return if (unit == 0) {
                "${bytes.coerceAtLeast(0)} ${units[unit]}"
            } else {
                String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
            }
        }
    }
}
