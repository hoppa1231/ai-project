package com.securevpn.app.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.app.NotificationCompat
import com.securevpn.app.MainActivity
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import io.nekohasekai.libbox.NetworkInterface as BoxNetworkInterface

class SingBoxVpnService : VpnService(), PlatformInterface, CommandServerHandler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var commandServer: CommandServer? = null
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var currentConfig: String? = null
    private var defaultInterfaceListener: InterfaceUpdateListener? = null
    private var defaultNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var initialized = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG).orEmpty()
                startForeground(NOTIFICATION_ID, buildNotification("Подключение..."))
                scope.launch {
                    runCatching { startCore(config) }
                        .onFailure { error ->
                            startForeground(
                                NOTIFICATION_ID,
                                buildNotification(error.message?.take(90) ?: "Ошибка запуска VPN")
                            )
                            closeCoreResources()
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                }
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
        startForeground(NOTIFICATION_ID, buildNotification("VPN запущен"))
    }

    private fun stopCore() {
        closeCoreResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeCoreResources() {
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        stopDefaultNetworkMonitor()
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null
        currentConfig = null
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

    private fun buildNotification(text: String): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("SecureVPN")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
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
        const val ACTION_STOP = "com.securevpn.app.vpn.STOP"
        const val EXTRA_CONFIG = "config"
        private const val CHANNEL_ID = "securevpn"
        private const val NOTIFICATION_ID = 42
        private const val FALLBACK_TUN_DNS = "172.19.0.2"
    }
}
