package app.chspch.client

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.chspch.client.data.BackendApi
import app.chspch.client.data.QuotaStatus
import app.chspch.client.data.RouteRule
import app.chspch.client.vpn.VpnNotificationSettings
import app.chspch.client.data.RoutingPolicyConflictException
import app.chspch.client.data.RoutingPolicy
import app.chspch.client.data.ServerNotification
import app.chspch.client.data.TelegramAuthData
import app.chspch.client.ui.theme.SecureVpnTheme
import app.chspch.client.vpn.SingBoxVpnService
import app.chspch.client.vpn.SingBoxTunnel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private var updateRequest by mutableStateOf(0)
    private var tileConnectRequest by mutableStateOf(0)
    private var sharedWebsite by mutableStateOf<SharedWebsite?>(null)
    private var sharedWebsiteError by mutableStateOf<String?>(null)
    private val telegramAuthEvents = MutableSharedFlow<TelegramAuthData>(replay = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = BurgundyDeep.toArgb()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        savedInstanceState?.getString("shared_destination")?.let { value ->
            sharedWebsite = SharedWebsite(value, savedInstanceState.getString("shared_match_type") ?: "DOMAIN_SUFFIX")
        }
        sharedWebsiteError = savedInstanceState?.getString("shared_error")
        setContent {
            SecureVpnTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = BurgundyDark) {
                    val displayedSharedWebsite = sharedWebsite
                    val displayedSharedError = sharedWebsiteError
                    Box {
                    SovietVpnApp(
                        tileConnectRequest = tileConnectRequest,
                        onCheckUpdates = { updateRequest++ },
                        telegramAuthEvents = telegramAuthEvents,
                        sharedWebsite = sharedWebsite,
                        sharedWebsiteError = sharedWebsiteError,
                        onSharedWebsiteConsumed = {
                            if (sharedWebsite == displayedSharedWebsite && sharedWebsiteError == displayedSharedError) {
                                sharedWebsite = null
                                sharedWebsiteError = null
                            }
                        }
                    )
                    app.chspch.client.update.AppUpdateHost(updateRequest)
                    }
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        sharedWebsite?.let {
            outState.putString("shared_destination", it.value)
            outState.putString("shared_match_type", it.matchType)
        }
        outState.putString("shared_error", sharedWebsiteError)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == app.chspch.client.vpn.VpnTileService.ACTION_CONNECT) {
            tileConnectRequest++
            intent.action = Intent.ACTION_MAIN
        }
        if (intent?.getBooleanExtra(app.chspch.client.update.AppUpdater.EXTRA_SHOW_UPDATE, false) == true) {
            updateRequest++
            intent.removeExtra(app.chspch.client.update.AppUpdater.EXTRA_SHOW_UPDATE)
        }
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            sharedWebsite = null
            sharedWebsiteError = null
            runCatching {
                SharedWebsiteParser.parse(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
            }.onSuccess { sharedWebsite = it }
                .onFailure { sharedWebsiteError = it.message ?: "Не удалось разобрать ссылку" }
            return
        }
        val payload = intent?.data?.takeIf { it.scheme == "securevpn" && it.host == "telegram-auth" }
            ?.getQueryParameter("payload")
            ?.let(::parseTelegramAuthPayload)
            ?: return
        telegramAuthEvents.tryEmit(payload)
    }

    private fun parseTelegramAuthPayload(raw: String): TelegramAuthData {
        val json = JSONObject(raw)
        return TelegramAuthData(
            id = json.getLong("id"),
            authDate = json.getLong("auth_date"),
            hash = json.getString("hash"),
            firstName = json.optStringOrNull("first_name"),
            lastName = json.optStringOrNull("last_name"),
            username = json.optStringOrNull("username"),
            photoUrl = json.optStringOrNull("photo_url")
        )
    }
}

private data class VpnLaunchProfile(
    val vlessUri: String?,
    val clientConfigJson: String?
)

@Composable
fun SovietVpnApp(
    tileConnectRequest: Int = 0,
    onCheckUpdates: () -> Unit = {},
    telegramAuthEvents: Flow<TelegramAuthData> = emptyFlow(),
    sharedWebsite: SharedWebsite? = null,
    sharedWebsiteError: String? = null,
    onSharedWebsiteConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val api = remember(context) { BackendApi(context) }
    var screen by rememberSaveable { mutableStateOf(AppScreen.Home) }
    var state by rememberSaveable { mutableStateOf(LinkState.Off) }
    var serviceControlledState by rememberSaveable { mutableStateOf(false) }
    var trafficText by rememberSaveable { mutableStateOf("") }
    var serverNodes by remember { mutableStateOf(api.cachedNodes().mapIndexed { index, node -> node.toServerNode(index) }) }
    var selectedServerId by rememberSaveable { mutableStateOf("") }
    var dnsCheck by rememberSaveable { mutableStateOf(true) }
    var cascadeMode by rememberSaveable { mutableStateOf(api.isCascadeModeEnabled()) }
    var autoConnect by rememberSaveable { mutableStateOf(true) }
    var killSwitch by rememberSaveable { mutableStateOf(false) }
    var darkRoom by rememberSaveable { mutableStateOf(true) }
    androidx.compose.runtime.SideEffect {
        (context as? Activity)?.let { activity ->
            androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .isAppearanceLightStatusBars = !darkRoom
        }
    }
    var notices by remember { mutableStateOf(VpnNotificationSettings.showTraffic(context)) }
    var apiNotice by remember { mutableStateOf<String?>(null) }
    var telegramLoginPending by remember { mutableStateOf(false) }
    var telegramAccount by remember { mutableStateOf(api.telegramAccount()) }
    var telegramNotice by remember { mutableStateOf<String?>(telegramAccount?.let { "вход выполнен: ${it.displayName}" }) }
    var routingPolicy by remember { mutableStateOf(api.cachedRoutingPolicy()) }
    var routingDraft by remember { mutableStateOf(routingPolicy) }
    var routingConflict by remember { mutableStateOf<RoutingPolicyConflictException?>(null) }
    var routingNotice by remember { mutableStateOf<String?>(null) }
    var quotaStatus by remember { mutableStateOf<QuotaStatus?>(null) }
    var serverNotification by remember { mutableStateOf<ServerNotification?>(null) }
    var showingTelegramConfigs by remember { mutableStateOf(false) }
    var selectedServerRequiresRotate by remember { mutableStateOf(false) }
    var userTouchedConnection by remember { mutableStateOf(false) }
    var trafficBytes by rememberSaveable { mutableStateOf(0L) }
    var runtimeQuotaTotalBytes by rememberSaveable { mutableStateOf(0L) }
    var configFailureRetry by rememberSaveable { mutableStateOf(0) }
    var autoConfigRetryCount by rememberSaveable { mutableStateOf(0) }
    var lastConfigFailureError by remember { mutableStateOf<String?>(null) }
    var bootstrapLoading by remember { mutableStateOf(false) }
    var bootstrapAttempted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notices = granted
        VpnNotificationSettings.setShowTraffic(context, granted)
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            apiNotice = "Разрешение на уведомления не выдано"
        }
    }
    val emptyServer = remember {
        ServerNode(
            id = "empty",
            city = "НЕТ СВЯЗИ",
            node = "УЗЛЫ НЕ ЗАГРУЖЕНЫ",
            region = "",
            ping = 0,
            load = 0,
            available = false
        )
    }
    val emptyTelegramServer = remember {
        ServerNode(
            id = "telegram-empty",
            city = "НЕТ КОНФИГОВ",
            node = "ТЕЛЕГРАМ НЕ НАШЕЛ ПРИВЯЗАННЫХ ЗАПИСЕЙ",
            region = "",
            ping = 0,
            load = 0,
            available = false
        )
    }
    val selectedServer = serverNodes.firstOrNull { it.id == selectedServerId }
        ?: serverNodes.firstOrNull()
        ?: if (showingTelegramConfigs) emptyTelegramServer else emptyServer
    lateinit var startConnection: () -> Unit
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startConnection()
        } else {
            state = LinkState.Off
            apiNotice = "Разрешение Android ВПН не выдано"
        }
    }

    fun applyVpnRuntimeState(snapshot: SingBoxVpnService.RuntimeSnapshot) {
        trafficText = snapshot.trafficText
        trafficBytes = snapshot.trafficBytes
        runtimeQuotaTotalBytes = snapshot.quotaTotalBytes
        val runtimeState = if (
            snapshot.state == SingBoxVpnService.STATE_CONNECTING &&
            snapshot.updatedAt > 0L &&
            System.currentTimeMillis() - snapshot.updatedAt > CONNECTING_STATE_TTL_MS
        ) {
            SingBoxVpnService.STATE_OFF
        } else {
            snapshot.state
        }
        state = when (runtimeState) {
            SingBoxVpnService.STATE_ON -> LinkState.On
            SingBoxVpnService.STATE_PAUSED -> LinkState.Paused
            SingBoxVpnService.STATE_CONNECTING -> LinkState.Connecting
            SingBoxVpnService.STATE_CONFIG_FAILED -> LinkState.Off
            else -> LinkState.Off
        }
        serviceControlledState = runtimeState != SingBoxVpnService.STATE_OFF &&
            runtimeState != SingBoxVpnService.STATE_CONFIG_FAILED
        if (runtimeState == SingBoxVpnService.STATE_CONFIG_FAILED && !snapshot.error.isNullOrBlank()) {
            lastConfigFailureError = snapshot.error
            apiNotice = "ВПН не запустился: ${snapshot.error.take(70)}"
        }
    }

    DisposableEffect(context) {
        applyVpnRuntimeState(SingBoxVpnService.runtimeState(context))
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != SingBoxVpnService.ACTION_STATE_CHANGED) return
                val nextState = intent.getStringExtra(SingBoxVpnService.EXTRA_STATE) ?: SingBoxVpnService.STATE_OFF
                applyVpnRuntimeState(
                    SingBoxVpnService.RuntimeSnapshot(
                        state = nextState,
                        trafficText = intent.getStringExtra(SingBoxVpnService.EXTRA_TRAFFIC).orEmpty(),
                        trafficBytes = intent.getLongExtra(SingBoxVpnService.EXTRA_TRAFFIC_BYTES, 0L),
                        quotaTotalBytes = intent.getLongExtra(SingBoxVpnService.EXTRA_QUOTA_TOTAL_BYTES, 0L),
                        updatedAt = intent.getLongExtra(SingBoxVpnService.EXTRA_UPDATED_AT, System.currentTimeMillis()),
                        error = intent.getStringExtra(SingBoxVpnService.EXTRA_ERROR)
                    )
                )
                if (nextState == SingBoxVpnService.STATE_CONFIG_FAILED) {
                    lastConfigFailureError = intent.getStringExtra(SingBoxVpnService.EXTRA_ERROR)
                    configFailureRetry += 1
                } else if (nextState == SingBoxVpnService.STATE_ON) {
                    autoConfigRetryCount = 0
                    lastConfigFailureError = null
                }
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(SingBoxVpnService.ACTION_STATE_CHANGED),
            flags
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    fun refreshBootstrap() {
        if (bootstrapLoading) return
        bootstrapLoading = true
        scope.launch {
            try {
                if (!context.hasInternetConnection()) {
                    serverNodes = serverNodes.map { it.copy(available = false) }
                    if (!userTouchedConnection && !serviceControlledState) {
                        state = LinkState.Off
                    }
                    apiNotice = "Нет интернета. Проверьте подключение и попробуйте снова"
                    return@launch
                }

                runCatching { api.bootstrap() }
                    .onSuccess { bootstrap ->
                        val apiNodes = bootstrap.nodes.sortedByDescending { it.health == "HEALTHY" }.mapIndexed { index, node -> node.toServerNode(index) }
                        showingTelegramConfigs = false
                        serverNodes = apiNodes
                        cascadeMode = api.isCascadeModeEnabled()
                        quotaStatus = bootstrap.quota
                        serverNotification = bootstrap.notifications.firstOrNull()
                        if (apiNodes.none { it.id == selectedServerId && it.available }) {
                            selectedServerId = apiNodes.firstOrNull { it.available }?.id ?: apiNodes.firstOrNull()?.id ?: selectedServerId
                        }
                        if (!userTouchedConnection && !serviceControlledState) state = LinkState.Off
                    }
                    .onFailure { error ->
                        serverNodes = serverNodes.map { it.copy(available = false) }
                        if (!userTouchedConnection && !serviceControlledState) state = LinkState.Off
                        apiNotice = if (!context.hasInternetConnection()) {
                            "Нет интернета. Проверьте подключение и попробуйте снова"
                        } else {
                            error.message?.take(90)?.let { "Не удалось обновить узлы: $it" } ?: "Не удалось обновить узлы. Сервер управления недоступен"
                        }
                    }
            } finally {
                bootstrapAttempted = true
                bootstrapLoading = false
            }
        }
    }

    fun refreshRoutingPolicy() {
        scope.launch {
            runCatching { api.loadRoutingPolicy() }
                .onSuccess {
                    routingPolicy = it
                    routingDraft = it
                    routingNotice = "маршруты синхронизированы"
                }
                .onFailure { error ->
                    routingNotice = error.message?.take(80) ?: "синхронизация не удалась · локальные правила сохранены"
                }
        }
    }

    LaunchedEffect(Unit) {
        if (
            notices && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        refreshBootstrap()
        refreshRoutingPolicy()
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            runCatching { api.loadCurrentQuota() }
                .onSuccess { quotaStatus = it }
            runCatching { api.loadCurrentNotifications() }
                .onSuccess { notifications ->
                    serverNotification = notifications.firstOrNull()
                }
        }
    }

    fun completeTelegramLogin(payload: TelegramAuthData) {
        telegramNotice = "Телеграм проверяется..."
        scope.launch {
            runCatching { api.loginWithTelegram(payload) }
                .onSuccess { account ->
                    runCatching { SingBoxTunnel(context).stop() }
                    state = LinkState.Off
                    serviceControlledState = false
                    telegramAccount = account
                    telegramNotice = "вход выполнен: ${account.displayName}"
                    apiNotice = "Телеграм-авторизация принята"
                    refreshBootstrap()
                    refreshRoutingPolicy()
                }
                .onFailure { error ->
                    telegramNotice = error.message?.take(70) ?: "Телеграм вход не удался"
                    apiNotice = telegramNotice
                }
        }
    }

    fun openTelegramLogin() {
        // Keep manual reauthentication available even when the saved account is still displayed.
        if (telegramLoginPending) return
        telegramLoginPending = true
        telegramNotice = "Открываем Телеграм…"
        scope.launch {
            try {
                val challenge = api.startTelegramAppLogin()
                val opened = runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(challenge.telegramAppUrl)))
                }.isSuccess
                if (!opened) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(challenge.telegramWebUrl)))
                telegramNotice = "Нажмите «Старт» в боте, затем вернитесь в приложение"
                val deadline = android.os.SystemClock.elapsedRealtime() + challenge.expiresIn.coerceIn(1, 300) * 1000
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    delay(2500)
                    val result = try {
                        api.pollTelegramAppLogin(challenge.challengeId)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        telegramNotice = "Ожидаем подтверждение. Проверяем связь с сервером…"
                        continue
                    }
                    if (result is app.chspch.client.data.TelegramAppLoginResult.Ready) {
                        SingBoxTunnel(context).stop()
                        state = LinkState.Off
                        serviceControlledState = false
                        telegramAccount = result.account
                        telegramNotice = "Вход через Телеграм выполнен"
                        apiNotice = telegramNotice
                        refreshBootstrap()
                        refreshRoutingPolicy()
                        return@launch
                    }
                }
                telegramNotice = "Время входа истекло. Нажмите ещё раз"
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                telegramNotice = "Вход через бота недоступен. Открываем страницу входа с включённым ВПН"
                val page = Uri.parse("${BuildConfig.API_BASE_URL}/auth/telegram/mobile-login")
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, page)) }
                    .onFailure { telegramNotice = "Не удалось открыть вход. Попробуйте ещё раз" }
            } finally {
                telegramLoginPending = false
            }
        }
    }

    LaunchedEffect(telegramAuthEvents) {
        telegramAuthEvents.collect(::completeTelegramLogin)
    }

    fun hasRoutingDraftChanges(): Boolean {
        return routingDraft.defaultRoute != routingPolicy.defaultRoute ||
            routingDraft.routeRules != routingPolicy.routeRules
    }

    fun quotaUsedBytes(): Long {
        val quotaBytes = quotaStatus?.usedBytes ?: 0L
        val telegramBytes = if (showingTelegramConfigs) {
            selectedServer.usedBytes
        } else {
            0L
        }
        return maxOf(quotaBytes, telegramBytes, trafficBytes)
    }

    fun quotaTotalBytes(): Long {
        quotaStatus?.totalBytes?.takeIf { it > 0L }?.let { return it }
        val configBytes = if (showingTelegramConfigs) selectedServer.totalBytes else 0L
        return maxOf(configBytes, runtimeQuotaTotalBytes)
    }

    suspend fun resolveVpnLaunchProfile(forceNew: Boolean = false): VpnLaunchProfile {
        if (telegramAccount != null && showingTelegramConfigs) selectedServer.vlessUri?.takeIf { it.isNotBlank() }?.let {
            return VpnLaunchProfile(vlessUri = it, clientConfigJson = null)
        }
        var forceRotateForCachedConfig = false
        if (!forceNew) {
            if (!api.activeConfigMatchesDefaultRouteMode() ||
                !api.activeConfigMatchesNode(selectedServer.id.substringBefore(":"))) {
                api.clearSavedActiveConfig()
                forceRotateForCachedConfig = true
            }
            // A locally cached credential can have been revoked by another session.
            // /vpn/issue validates server-side state and reuses only an active config.
        }
        val issued = api.issueVpnConfig(
            region = selectedServer.region.takeIf { it.isNotBlank() },
            exitNodeId = selectedServer.id.substringBefore(":").takeIf { it.isNotBlank() },
            forceRotate = forceNew || forceRotateForCachedConfig || selectedServerRequiresRotate
        )
        selectedServerRequiresRotate = false
        val clientConfigJson = issued.clientConfigJson?.takeIf { it.isNotBlank() }
        val vlessUri = issued.vlessUri?.takeIf { it.isNotBlank() }
        require(clientConfigJson != null || vlessUri != null) { "Сервер не вернул ВПН-конфиг" }
        return VpnLaunchProfile(vlessUri = vlessUri, clientConfigJson = clientConfigJson)
    }

    suspend fun startTunnelWithConfig(policyToUse: RoutingPolicy, forceNewConfig: Boolean = false) {
        val policy = if (telegramAccount == null) RoutingPolicy.telegramOnly() else policyToUse
        val launchProfile = resolveVpnLaunchProfile(forceNew = forceNewConfig)
        val tunnel = SingBoxTunnel(context)
        val clientConfigJson = launchProfile.clientConfigJson
        if (clientConfigJson != null) {
            tunnel.startWithClientConfig(
                clientConfigJson,
                policy,
                quotaUsedBytes = quotaUsedBytes(),
                quotaTotalBytes = 0L
            )
        } else {
            tunnel.start(
                requireNotNull(launchProfile.vlessUri) { "Сервер не вернул VLESS-конфиг" },
                policy,
                quotaUsedBytes = quotaUsedBytes(),
                quotaTotalBytes = 0L
            )
        }
    }

    LaunchedEffect(configFailureRetry) {
        if (configFailureRetry <= 0) return@LaunchedEffect
        if (autoConfigRetryCount >= 1) {
            state = LinkState.Off
            apiNotice = lastConfigFailureError?.let { "ВПН не запустился: ${it.take(70)}" }
                ?: "Новый конфиг тоже не запустился"
            return@LaunchedEffect
        }
        autoConfigRetryCount += 1
        state = LinkState.Connecting
        apiNotice = "Конфиг не заработал, запрашиваем новый..."
        runCatching {
            api.clearSavedActiveConfig()
            val policy = routingPolicy
            startTunnelWithConfig(policy, forceNewConfig = true)
        }
            .onSuccess {
                serviceControlledState = true
                state = LinkState.Connecting
                apiNotice = "Проверяем новый конфиг..."
            }
            .onFailure { error ->
                serviceControlledState = false
                state = LinkState.Off
                apiNotice = error.message?.take(90) ?: "Не удалось получить новый конфиг"
            }
    }

    startConnection = {
        autoConfigRetryCount = 0
        if (!context.hasInternetConnection()) {
            state = LinkState.Off
            serviceControlledState = false
            apiNotice = "Нет интернета. ВПН не запущен"
        } else if (!selectedServer.available || selectedServer.id == "empty") {
            state = LinkState.Off
            serviceControlledState = false
            apiNotice = "Нет доступного ВПН-узла. Проверьте подключение"
        } else if (showingTelegramConfigs && !selectedServer.available) {
            state = LinkState.Off
            apiNotice = "Выбранный Телеграм-конфиг отключен на ноде"
        } else {
            state = LinkState.Connecting
            scope.launch {
                runCatching {
                    val policy = run {
                        runCatching { api.loadRoutingPolicy() }
                            .onSuccess {
                                routingPolicy = it
                                routingDraft = it
                            }
                            .getOrElse {
                                api.cachedRoutingPolicy().also { cached ->
                                    routingPolicy = cached
                                    routingDraft = cached
                                }
                            }
                    }
                    runCatching { startTunnelWithConfig(policy) }
                        .getOrElse {
                            api.clearSavedActiveConfig()
                            startTunnelWithConfig(policy, forceNewConfig = true)
                        }
                }
                    .onSuccess {
                        state = LinkState.On
                        serviceControlledState = true
                        apiNotice = "ВПН запущен: ${selectedServer.city}"
                    }
                    .onFailure { error ->
                        runCatching { SingBoxTunnel(context).stop() }
                        state = LinkState.Off
                        serviceControlledState = false
                        apiNotice = error.message?.take(90) ?: "Не удалось запустить ВПН"
                    }
            }
        }
    }

    fun saveRoutingPolicy(nextPolicy: RoutingPolicy) {
        routingNotice = "сохраняем маршруты..."
        scope.launch {
            runCatching { api.updateRoutingPolicy(nextPolicy) }
                .onSuccess { saved ->
                    routingPolicy = saved
                    routingDraft = saved
                    routingNotice = "маршруты утверждены"
                    if (state == LinkState.On || state == LinkState.Paused) {
                        runCatching {
                            startTunnelWithConfig(saved)
                        }.onFailure { error ->
                            apiNotice = error.message?.take(90) ?: "ВПН требует перезапуска"
                        }
                    }
                }
                .onFailure { error ->
                    routingDraft = nextPolicy
                    if (error is RoutingPolicyConflictException) {
                        routingConflict = error
                        routingNotice = "Правила изменились на сервере · черновик сохранён"
                    } else {
                        routingNotice = error.message?.take(90) ?: "Не сохранено: сервер недоступен"
                    }
                }
        }
    }

    fun syncRoutingPolicy() {
        if (hasRoutingDraftChanges()) {
            saveRoutingPolicy(routingDraft)
        } else {
            refreshRoutingPolicy()
        }
    }

    fun dismissServerNotification(notification: ServerNotification) {
        serverNotification = null
        scope.launch { runCatching { api.markNotificationRead(notification.id) } }
    }

    fun updateRoutingDraft(nextPolicy: RoutingPolicy) {
        routingDraft = nextPolicy
        routingNotice = "Изменения ожидают сохранения на сервере"
    }

    fun toggleRouteRule(ruleId: String) {
        updateRoutingDraft(
            routingDraft.copy(
                routeRules = routingDraft.routeRules.map { rule ->
                    if (rule.id == ruleId) rule.copy(enabled = !rule.enabled) else rule
                }
            )
        )
    }

    fun addRoutingRule(matchType: String, action: String, rawValues: String) {
        val normalizedInput = rawValues.trim()
        val actualType = if (matchType == "APP_PACKAGE") matchType else
            if (normalizedInput.contains(':') || normalizedInput.substringBefore('/').split('.').let { it.size == 4 && it.all { part -> part.toIntOrNull() in 0..255 } }) "IP_CIDR" else "DOMAIN_SUFFIX"
        val input = if (actualType == "IP_CIDR" && '/' !in normalizedInput) normalizedInput + if (':' in normalizedInput) "/128" else "/32" else normalizedInput
        val values = parseRouteRuleValues(input, actualType)
        if (values.isEmpty()) {
            routingNotice = "укажите значения"
            return
        }
        if (routingDraft.routeRules.any { it.matchType == actualType && it.values == values }) {
            routingNotice = "Сервис или приложение уже есть в списке"
            return
        }
        val nextPriority = (routingDraft.routeRules.maxOfOrNull { it.priority } ?: 500) + 100
        val rule = RouteRule(
            id = "local-${System.nanoTime()}",
            source = "USER",
            defaultRuleKey = null,
            name = values.firstOrNull() ?: routeRuleName(actualType, action),
            description = "",
            enabled = true,
            priority = nextPriority,
            matchType = actualType,
            values = values,
            action = action,
            editable = true
        )
        updateRoutingDraft(routingDraft.copy(routeRules = routingDraft.routeRules + rule))
    }

    fun deleteRouteRule(ruleId: String) {
        updateRoutingDraft(
            routingDraft.copy(routeRules = routingDraft.routeRules.filterNot { it.id == ruleId && it.source == "USER" })
        )
    }

    fun toggleCascadeMode() {
        if (telegramAccount == null) {
            telegramNotice = "Войдите через Телеграм для изменения маршрута"
            return
        }
        val nextEnabled = !cascadeMode
        cascadeMode = nextEnabled
        api.setCascadeModeEnabled(nextEnabled)
        apiNotice = if (nextEnabled) {
            "Каскадный режим включен"
        } else {
            "Каскадный режим выключен"
        }
        if (state == LinkState.On || state == LinkState.Paused) {
            state = LinkState.Connecting
            scope.launch {
                runCatching {
                    val policy = routingPolicy
                    startTunnelWithConfig(policy, forceNewConfig = true)
                }
                    .onSuccess {
                        state = LinkState.On
                        serviceControlledState = true
                        apiNotice = if (nextEnabled) {
                            "ВПН переведен в каскадный режим"
                        } else {
                            "ВПН переведен на один узел"
                        }
                    }
                    .onFailure { error ->
                        runCatching { SingBoxTunnel(context).stop() }
                        state = LinkState.Off
                        serviceControlledState = false
                        apiNotice = error.message?.take(90) ?: "Не удалось сменить режим ВПН"
                    }
            }
        }
    }

    fun cycleConnection() {
        userTouchedConnection = true
        if (state == LinkState.Connecting) {
            state = LinkState.Off
            serviceControlledState = false
            apiNotice = "Подключение сброшено"
            scope.launch { runCatching { SingBoxTunnel(context).stop() } }
            return
        }
        if (state == LinkState.Off) {
            if (!context.hasInternetConnection()) {
                apiNotice = "Нет интернета. Проверьте подключение и попробуйте снова"
                return
            }
            if (!selectedServer.available || selectedServer.id == "empty") {
                apiNotice = if (bootstrapLoading) "Загружаем список ВПН-узлов..." else "Нет доступного ВПН-узла. Обновляем список..."
                refreshBootstrap()
                return
            }
            if (showingTelegramConfigs && !selectedServer.available) {
                apiNotice = "Выбранный Телеграм-конфиг отключен на ноде"
                return
            }
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                state = LinkState.Connecting
                vpnPermissionLauncher.launch(prepareIntent)
                return
            }
            if (
                notices && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            startConnection()
        } else {
            state = LinkState.Connecting
            scope.launch {
                runCatching { SingBoxTunnel(context).stop() }
                    .onSuccess {
                        state = LinkState.Off
                        serviceControlledState = false
                        apiNotice = "ВПН остановлен"
                    }
                    .onFailure { error ->
                        state = LinkState.Off
                        serviceControlledState = false
                        apiNotice = error.message?.take(90) ?: "Не удалось остановить ВПН"
                    }
            }
        }
    }

    var handledTileConnectRequest by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(tileConnectRequest, bootstrapLoading, bootstrapAttempted) {
        if (tileConnectRequest > handledTileConnectRequest && bootstrapAttempted && !bootstrapLoading) {
            handledTileConnectRequest = tileConnectRequest
            screen = AppScreen.Home
            if (state == LinkState.Off) cycleConnection()
        }
    }

    @Composable
    fun ScreenContent(targetScreen: AppScreen) {
        when (targetScreen) {
            AppScreen.Home -> HomeScreen(
                linkState = state,
                server = selectedServer,
                apiNotice = apiNotice,
                nightTheme = darkRoom,
                onToggle = ::cycleConnection,
                onServers = { screen = AppScreen.Servers }
            )

            AppScreen.Servers -> ServersScreen(
                servers = serverNodes,
                selectedId = selectedServerId,
                nightTheme = darkRoom,
                telegramMode = showingTelegramConfigs,
                onBack = { screen = AppScreen.Home },
                onSelect = {
                    if (selectedServerId != it) {
                        selectedServerId = it
                        selectedServerRequiresRotate = true
                        api.clearSavedActiveConfig()
                    }
                }
            )

            AppScreen.Settings -> SettingsScreen(
                dnsCheck = dnsCheck,
                cascadeMode = cascadeMode,
                autoConnect = autoConnect,
                killSwitch = killSwitch,
                darkRoom = darkRoom,
                notices = notices,
                routeRulesCount = routingDraft.routeRules.count { it.enabled },
                telegramAccount = telegramAccount,
                telegramStatus = telegramNotice,
                onDns = { dnsCheck = !dnsCheck },
                onCascade = ::toggleCascadeMode,
                onAuto = { autoConnect = !autoConnect },
                onKill = { killSwitch = !killSwitch },
                onDark = { darkRoom = !darkRoom },
                onNotices = {
                    val enabled = !notices
                    notices = enabled
                    VpnNotificationSettings.setShowTraffic(context, enabled)
                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onRouting = { screen = AppScreen.Routing },
                onTelegramLogin = ::openTelegramLogin,
                onCheckUpdates = onCheckUpdates
            )

            AppScreen.Routing -> RoutingScreen(
                policy = routingDraft,
                canEdit = telegramAccount != null,
                onTelegramLogin = ::openTelegramLogin,
                notice = routingNotice,
                hasUnsyncedChanges = hasRoutingDraftChanges(),
                nightTheme = darkRoom,
                onBack = { screen = AppScreen.Settings },
                onSync = ::syncRoutingPolicy,
                onToggleRule = ::toggleRouteRule,
                onAddRule = ::addRoutingRule,
                onDeleteRule = ::deleteRouteRule
            )

            AppScreen.Speed -> SpeedScreen(nightTheme = darkRoom)
        }
    }

    var savingSharedWebsite by remember(sharedWebsite) { mutableStateOf(false) }
    var sharedSaveError by remember(sharedWebsite) { mutableStateOf<String?>(null) }
    if (sharedWebsiteError != null) {
        AlertDialog(
            onDismissRequest = onSharedWebsiteConsumed,
            title = { Text("Не удалось добавить сайт") },
            text = { Text(sharedWebsiteError) },
            confirmButton = { TextButton(onClick = onSharedWebsiteConsumed) { Text("Закрыть") } }
        )
    } else if (sharedWebsite != null) {
        AlertDialog(
            onDismissRequest = { if (!savingSharedWebsite) onSharedWebsiteConsumed() },
            title = { Text("Добавить в ВПН?") },
            text = {
                Column {
                    Text(sharedWebsite.value)
                    Text(if (sharedWebsite.matchType == "DOMAIN_SUFFIX")
                        "Этот домен и его поддомены будут открываться через ВПН. Остальной трафик — напрямую."
                        else "Этот сетевой адрес будет открываться через ВПН. Остальной трафик — напрямую.")
                    if (telegramAccount == null) Text("Сначала войдите через Телеграм. Затем подтвердите добавление.")
                    if (hasRoutingDraftChanges()) Text("Будут сохранены также ваши изменения в списке маршрутов.")
                    sharedSaveError?.let { Text(it) }
                }
            },
            confirmButton = {
                TextButton(enabled = !savingSharedWebsite, onClick = {
                    if (telegramAccount == null) {
                        openTelegramLogin()
                    } else if (!savingSharedWebsite) {
                        savingSharedWebsite = true
                        sharedSaveError = null
                        scope.launch {
                            try {
                                val base = if (hasRoutingDraftChanges()) routingDraft else api.loadRoutingPolicy()
                                val covered = base.routeRules.any { rule ->
                                    rule.enabled && rule.action == "VPN" && rule.values.any { value ->
                                        (rule.matchType == sharedWebsite.matchType && value == sharedWebsite.value) ||
                                            (rule.matchType == "DOMAIN_SUFFIX" && sharedWebsite.matchType == "DOMAIN_SUFFIX" &&
                                                sharedWebsite.value.endsWith(".$value"))
                                    }
                                }
                                val saved = if (covered && !hasRoutingDraftChanges()) base else {
                                    val existing = base.routeRules.firstOrNull {
                                        it.matchType == sharedWebsite.matchType && it.values == listOf(sharedWebsite.value) && it.editable
                                    }
                                    val rules = if (covered) base.routeRules else if (existing != null) {
                                        base.routeRules.map { if (it.id == existing.id) it.copy(enabled = true, action = "VPN") else it }
                                    } else base.routeRules + RouteRule(
                                        id = "local-${System.nanoTime()}", source = "USER", defaultRuleKey = null,
                                        name = sharedWebsite.value, description = "", enabled = true,
                                        priority = (base.routeRules.maxOfOrNull { it.priority } ?: 500) + 100,
                                        matchType = sharedWebsite.matchType, values = listOf(sharedWebsite.value), action = "VPN"
                                    )
                                    api.updateRoutingPolicy(base.copy(defaultRoute = "DIRECT", routeRules = rules))
                                }
                                val shouldApply = saved != routingPolicy
                                routingPolicy = saved
                                routingDraft = saved
                                routingNotice = if (covered) "Сайт уже добавлен в ВПН" else "Сайт добавлен в ВПН"
                                screen = AppScreen.Routing
                                onSharedWebsiteConsumed()
                                if (shouldApply && (state == LinkState.On || state == LinkState.Paused)) {
                                    runCatching { startTunnelWithConfig(saved) }.onFailure {
                                        apiNotice = "Правило сохранено. Переподключите ВПН."
                                    }
                                }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                sharedSaveError = error.message?.take(160) ?: "Не удалось сохранить. Попробуйте ещё раз."
                            } finally {
                                savingSharedWebsite = false
                            }
                        }
                    }
                }) { Text(if (savingSharedWebsite) "Сохраняем…" else if (telegramAccount == null) "Войти через Телеграм" else "Добавить") }
            },
            dismissButton = { TextButton(enabled = !savingSharedWebsite, onClick = onSharedWebsiteConsumed) { Text("Отмена") } }
        )
    }

    routingConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = { routingConflict = null },
            title = { Text("Правила изменились") },
            text = { Text("На сервере уже другая версия маршрутов. Ваш черновик не отправлен и остаётся на экране. Загрузить актуальные правила? Это заменит несохранённые изменения.") },
            confirmButton = {
                TextButton(onClick = {
                    routingConflict = null
                    val latest = conflict.latestPolicy
                    if (latest != null) {
                        routingPolicy = latest
                        routingDraft = latest
                        routingNotice = "Актуальные правила загружены · повторите нужные изменения"
                    } else {
                        refreshRoutingPolicy()
                    }
                }) { Text("Загрузить правила") }
            },
            dismissButton = {
                TextButton(onClick = { routingConflict = null }) { Text("Оставить черновик") }
            }
        )
    }

    var swipeOffsetPx by remember { mutableFloatStateOf(0f) }
    var swipeWidthPx by remember { mutableFloatStateOf(1f) }
    var swipeAnimating by remember { mutableStateOf(false) }

    fun finishSwipe() {
        if (swipeAnimating) return
        val direction = if (swipeOffsetPx < 0f) 1 else -1
        val target = screen.swipeTarget(direction)
        val shouldChange = target != screen && abs(swipeOffsetPx) >= swipeWidthPx * 0.18f
        swipeAnimating = true
        scope.launch {
            val destination = if (shouldChange) {
                if (direction > 0) -swipeWidthPx else swipeWidthPx
            } else {
                0f
            }
            Animatable(swipeOffsetPx).animateTo(destination, tween(190)) {
                swipeOffsetPx = value
            }
            if (shouldChange) screen = target
            swipeOffsetPx = 0f
            swipeAnimating = false
        }
    }

    BackHandler(enabled = screen == AppScreen.Routing || screen == AppScreen.Servers) {
        screen = if (screen == AppScreen.Servers) AppScreen.Home else AppScreen.Settings
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BurgundyDeep)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { swipeWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(screen) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            if (!swipeAnimating) swipeOffsetPx = 0f
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            if (!swipeAnimating) {
                                change.consume()
                                val candidate = (swipeOffsetPx + dragAmount).coerceIn(-swipeWidthPx, swipeWidthPx)
                                val direction = if (candidate < 0f) 1 else -1
                                swipeOffsetPx = if (screen.swipeTarget(direction) == screen) candidate * 0.22f else candidate
                            }
                        },
                        onDragEnd = ::finishSwipe,
                        onDragCancel = ::finishSwipe
                    )
                }
        ) {
            // Capture content and translation in the same composition snapshot. Reading
            // the live offset in graphicsLayer can reset the old screen to x=0 before
            // recomposition has replaced its content at the end of the gesture.
            val frameOffsetPx = swipeOffsetPx
            val swipeDirection = if (frameOffsetPx < 0f) 1 else -1
            val isSwiping = frameOffsetPx != 0f
            val adjacentScreen = screen.swipeTarget(swipeDirection)
            if (isSwiping && adjacentScreen != screen) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = frameOffsetPx + if (swipeDirection > 0) swipeWidthPx else -swipeWidthPx
                        }
                ) {
                    ScreenContent(adjacentScreen)
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = frameOffsetPx }
            ) {
                ScreenContent(screen)
            }

            serverNotification?.let { notification ->
            AlertDialog(
                onDismissRequest = {
                    dismissServerNotification(notification)
                },
                title = { Text(notification.title.ifBlank { "Сообщение от сервера" }) },
                text = { Text(notification.body.ifBlank { notification.displayText }) },
                confirmButton = {
                    Button(
                        onClick = {
                            dismissServerNotification(notification)
                        }
                    ) {
                        Text("Понятно")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            dismissServerNotification(notification)
                        }
                    ) {
                        Text("Закрыть")
                    }
                }
            )
            }

        }

        BottomNav(
            current = when (screen) {
                AppScreen.Servers -> AppScreen.Home
                AppScreen.Routing -> AppScreen.Settings
                else -> screen
            },
            onScreen = { screen = it },
            light = !darkRoom,
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 8.dp)
        )
    }
}

private fun JSONObject.optStringOrNull(name: String): String? {
    return optString(name).takeIf { it.isNotBlank() }
}

private fun parseRouteRuleValues(raw: String, matchType: String): List<String> {
    return raw.split(',', ';', '\n', '\t', ' ')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { value ->
            when (matchType) {
                "DOMAIN", "DOMAIN_SUFFIX", "DOMAIN_KEYWORD" -> value.removePrefix(".").lowercase()
                "GEOIP" -> value.lowercase().removePrefix("geoip-")
                "APP_PACKAGE" -> value
                else -> value
            }
        }
        .filter { it.isNotBlank() }
        .distinct()
}

private fun routeRuleName(matchType: String, action: String): String {
    val subject = when (matchType) {
        "DOMAIN" -> "Домен"
        "DOMAIN_KEYWORD" -> "Слова домена"
        "IP_CIDR" -> "сети адресов"
        "APP_PACKAGE" -> "Приложения"
        "GEOIP" -> "География адресов"
        else -> "Домены"
    }
    val route = when (action) {
        "DIRECT" -> "напрямую"
        "BLOCK" -> "блок"
        else -> "через ВПН"
    }
    return "$subject $route"
}

private const val CONNECTING_STATE_TTL_MS = 45_000L

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun SovietVpnPreview() {
    SecureVpnTheme {
        SovietVpnApp()
    }
}
