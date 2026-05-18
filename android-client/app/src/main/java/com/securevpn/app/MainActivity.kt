package com.securevpn.app

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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.securevpn.app.data.BackendApi
import com.securevpn.app.data.QuotaStatus
import com.securevpn.app.data.RouteRule
import com.securevpn.app.data.RoutingPolicy
import com.securevpn.app.data.TelegramAuthData
import com.securevpn.app.ui.theme.SecureVpnTheme
import com.securevpn.app.vpn.SingBoxVpnService
import com.securevpn.app.vpn.SingBoxTunnel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private val telegramAuthEvents = MutableSharedFlow<TelegramAuthData>(replay = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SecureVpnTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = BurgundyDark) {
                    SovietVpnApp(telegramAuthEvents = telegramAuthEvents)
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
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

@Composable
fun SovietVpnApp(
    telegramAuthEvents: Flow<TelegramAuthData> = emptyFlow()
) {
    val context = LocalContext.current
    val api = remember(context) { BackendApi(context) }
    var screen by rememberSaveable { mutableStateOf(AppScreen.Home) }
    var state by rememberSaveable { mutableStateOf(LinkState.Off) }
    var serviceControlledState by rememberSaveable { mutableStateOf(false) }
    var trafficText by rememberSaveable { mutableStateOf("") }
    var serverNodes by remember { mutableStateOf(ServerNodes) }
    var selectedServerId by rememberSaveable { mutableStateOf(ServerNodes.first().id) }
    var dnsCheck by rememberSaveable { mutableStateOf(true) }
    var autoConnect by rememberSaveable { mutableStateOf(true) }
    var killSwitch by rememberSaveable { mutableStateOf(false) }
    var darkRoom by rememberSaveable { mutableStateOf(true) }
    var notices by rememberSaveable { mutableStateOf(false) }
    var apiNotice by remember { mutableStateOf<String?>(null) }
    var telegramAccount by remember { mutableStateOf(api.telegramAccount()) }
    var telegramNotice by remember { mutableStateOf<String?>(telegramAccount?.let { "вход выполнен: ${it.displayName}" }) }
    var routingPolicy by remember { mutableStateOf(api.cachedRoutingPolicy()) }
    var routingDraft by remember { mutableStateOf(routingPolicy) }
    var routingNotice by remember { mutableStateOf<String?>(null) }
    var quotaStatus by remember { mutableStateOf<QuotaStatus?>(null) }
    var showingTelegramConfigs by remember { mutableStateOf(telegramAccount != null) }
    var userTouchedConnection by remember { mutableStateOf(false) }
    var trafficBytes by rememberSaveable { mutableStateOf(0L) }
    var runtimeQuotaTotalBytes by rememberSaveable { mutableStateOf(0L) }
    var configFailureRetry by rememberSaveable { mutableStateOf(0) }
    var autoConfigRetryCount by rememberSaveable { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notices = granted
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            apiNotice = "Разрешение на уведомления не выдано"
        }
    }
    val emptyTelegramServer = remember {
        ServerNode(
            id = "telegram-empty",
            city = "НЕТ КОНФИГОВ",
            node = "TELEGRAM НЕ НАШЕЛ ПРИВЯЗАННЫХ ЗАПИСЕЙ",
            region = "",
            ping = 0,
            load = 0
        )
    }
    val selectedServer = serverNodes.firstOrNull { it.id == selectedServerId }
        ?: serverNodes.firstOrNull()
        ?: if (showingTelegramConfigs) emptyTelegramServer else ServerNodes.first()
    lateinit var startConnection: () -> Unit
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startConnection()
        } else {
            state = LinkState.Off
            apiNotice = "Разрешение Android VPN не выдано"
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
                        updatedAt = intent.getLongExtra(SingBoxVpnService.EXTRA_UPDATED_AT, System.currentTimeMillis())
                    )
                )
                if (nextState == SingBoxVpnService.STATE_CONFIG_FAILED) {
                    configFailureRetry += 1
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
        scope.launch {
            val linkedAccount = telegramAccount
            if (linkedAccount != null) {
                runCatching { api.loadTelegramConfigs() }
                    .onSuccess { telegramConfigs ->
                        showingTelegramConfigs = true
                        val nextServers = telegramConfigs.mapIndexed { index, config -> config.toServerNode(index) }
                        serverNodes = nextServers
                        if (nextServers.none { it.id == selectedServerId }) {
                            selectedServerId = nextServers.firstOrNull()?.id ?: selectedServerId
                        }
                        if (!userTouchedConnection && !serviceControlledState) {
                            state = LinkState.Off
                        }
                        apiNotice = if (nextServers.isEmpty()) {
                            "Для Telegram ${linkedAccount.displayName} конфиги пока не найдены"
                        } else {
                            null
                        }
                    }
                    .onFailure { error ->
                        showingTelegramConfigs = true
                        serverNodes = emptyList()
                        if (!userTouchedConnection && !serviceControlledState) {
                            state = LinkState.Off
                        }
                        apiNotice = error.message?.take(90) ?: "Не удалось загрузить Telegram-конфиги"
                    }
                return@launch
            }

            runCatching { api.bootstrap() }
                .onSuccess { bootstrap ->
                    val apiNodes = bootstrap.nodes.mapIndexed { index, node -> node.toServerNode(index) }
                    showingTelegramConfigs = false
                    serverNodes = apiNodes
                    quotaStatus = bootstrap.quota
                    if (apiNodes.none { it.id == selectedServerId }) {
                        selectedServerId = apiNodes.firstOrNull()?.id ?: selectedServerId
                    }
                    if (!userTouchedConnection && !serviceControlledState) state = LinkState.Off
                    apiNotice = null
                }
                .onFailure { error ->
                    if (!userTouchedConnection && !serviceControlledState) state = LinkState.Off
                    apiNotice = error.message?.take(90) ?: "API недоступен"
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
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notices = true
        }
        refreshBootstrap()
        refreshRoutingPolicy()
    }

    fun completeTelegramLogin(payload: TelegramAuthData) {
        telegramNotice = "Telegram проверяется..."
        scope.launch {
            runCatching { api.loginWithTelegram(payload) }
                .onSuccess { account ->
                    telegramAccount = account
                    telegramNotice = "вход выполнен: ${account.displayName}"
                    apiNotice = "Telegram-авторизация принята"
                    refreshBootstrap()
                }
                .onFailure { error ->
                    telegramNotice = error.message?.take(70) ?: "Telegram вход не удался"
                    apiNotice = telegramNotice
                }
        }
    }

    fun openTelegramLogin() {
        if (telegramAccount != null) {
            apiNotice = "Обновляем Telegram-конфиги..."
            refreshBootstrap()
            return
        }
        telegramNotice = "открываем Telegram..."
        val webLoginUrl = Uri.parse("${BuildConfig.API_BASE_URL}/auth/telegram/login")
            .buildUpon()
            .appendQueryParameter("return_to", "securevpn://telegram-auth")
            .build()
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, webLoginUrl))
        }.onFailure { error ->
            telegramNotice = error.message?.take(70) ?: "Не удалось открыть Telegram"
            apiNotice = telegramNotice
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
        val quotaBytes = quotaStatus?.totalBytes ?: 0L
        val telegramBytes = if (showingTelegramConfigs) {
            selectedServer.totalBytes
        } else {
            0L
        }
        return maxOf(quotaBytes, telegramBytes, runtimeQuotaTotalBytes)
    }

    suspend fun resolveVlessUri(forceNew: Boolean = false): String {
        selectedServer.vlessUri?.takeIf { it.isNotBlank() }?.let { return it }
        if (!forceNew) {
            api.activeVlessUri()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val issued = api.issueVpnConfig(
            region = selectedServer.region.takeIf { it.isNotBlank() },
            exitNodeId = selectedServer.id.substringBefore(":").takeIf { it.isNotBlank() }
        )
        return requireNotNull(issued.vlessUri?.takeIf { it.isNotBlank() }) { "Сервер не вернул VLESS-конфиг" }
    }

    suspend fun startTunnelWithConfig(policy: RoutingPolicy, forceNewConfig: Boolean = false) {
        val vlessUri = resolveVlessUri(forceNew = forceNewConfig)
        SingBoxTunnel(context).start(
            vlessUri,
            policy,
            quotaUsedBytes = quotaUsedBytes(),
            quotaTotalBytes = quotaTotalBytes()
        )
    }

    LaunchedEffect(configFailureRetry) {
        if (configFailureRetry <= 0) return@LaunchedEffect
        if (autoConfigRetryCount >= 1) {
            state = LinkState.Off
            apiNotice = "Новый конфиг тоже не запустился"
            return@LaunchedEffect
        }
        autoConfigRetryCount += 1
        state = LinkState.Connecting
        apiNotice = "Конфиг не заработал, запрашиваем новый..."
        runCatching {
            api.clearSavedActiveConfig()
            val policy = if (hasRoutingDraftChanges()) routingDraft else api.cachedRoutingPolicy()
            startTunnelWithConfig(policy, forceNewConfig = true)
        }
            .onSuccess {
                serviceControlledState = true
                autoConfigRetryCount = 0
                state = LinkState.On
                apiNotice = "Новый конфиг применен"
            }
            .onFailure { error ->
                serviceControlledState = false
                state = LinkState.Off
                apiNotice = error.message?.take(90) ?: "Не удалось получить новый конфиг"
            }
    }

    startConnection = {
        autoConfigRetryCount = 0
        if (showingTelegramConfigs && !selectedServer.available) {
            state = LinkState.Off
            apiNotice = "Выбранный Telegram-конфиг отключен на ноде"
        } else {
            state = LinkState.Connecting
            scope.launch {
                runCatching {
                    val policy = if (hasRoutingDraftChanges()) {
                        routingDraft
                    } else {
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
                        apiNotice = "VPN запущен: ${selectedServer.city}"
                    }
                    .onFailure { error ->
                        runCatching { SingBoxTunnel(context).stop() }
                        state = LinkState.Off
                        serviceControlledState = false
                        apiNotice = error.message?.take(90) ?: "Не удалось запустить VPN"
                    }
            }
        }
    }

    fun saveRoutingPolicy(nextPolicy: RoutingPolicy) {
        routingNotice = "сохраняем маршруты..."
        scope.launch {
            if (state == LinkState.On || state == LinkState.Paused) {
                runCatching { startTunnelWithConfig(nextPolicy) }
                delay(350)
            }
            runCatching { api.updateRoutingPolicy(nextPolicy) }
                .onSuccess { saved ->
                    routingPolicy = saved
                    routingDraft = saved
                    routingNotice = "маршруты утверждены"
                    if (state == LinkState.On || state == LinkState.Paused) {
                        runCatching {
                            startTunnelWithConfig(saved)
                        }.onFailure { error ->
                            apiNotice = error.message?.take(90) ?: "VPN требует перезапуска"
                        }
                    }
                }
                .onFailure { error ->
                    routingDraft = nextPolicy
                    routingNotice = error.message?.take(90) ?: "локально применено, сервер недоступен"
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

    fun updateRoutingDraft(nextPolicy: RoutingPolicy) {
        routingDraft = nextPolicy
        routingNotice = if (state == LinkState.On || state == LinkState.Paused) {
            "применено локально · синхронизация позже"
        } else {
            "изменено локально · синхронизация позже"
        }
        if (state == LinkState.On || state == LinkState.Paused) {
            scope.launch {
                runCatching { startTunnelWithConfig(nextPolicy) }
                    .onFailure { error ->
                        apiNotice = error.message?.take(90) ?: "Локальные маршруты требуют перезапуска"
                    }
            }
        }
    }

    fun toggleDefaultRoute() {
        updateRoutingDraft(
            routingDraft.copy(
                defaultRoute = if (routingDraft.defaultRoute == "VPN") "DIRECT" else "VPN"
            )
        )
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

    fun cycleRouteRuleAction(ruleId: String) {
        val actions = listOf("VPN", "DIRECT", "BLOCK")
        updateRoutingDraft(
            routingDraft.copy(
                routeRules = routingDraft.routeRules.map { rule ->
                    if (rule.id == ruleId) {
                        val nextAction = actions[(actions.indexOf(rule.action).coerceAtLeast(0) + 1) % actions.size]
                        rule.copy(action = nextAction)
                    } else {
                        rule
                    }
                }
            )
        )
    }

    fun addRoutingRule(matchType: String, action: String, rawValues: String) {
        val values = parseRouteRuleValues(rawValues, matchType)
        if (values.isEmpty()) {
            routingNotice = "укажите значения"
            return
        }
        val nextPriority = (routingDraft.routeRules.maxOfOrNull { it.priority } ?: 500) + 100
        val rule = RouteRule(
            id = "local-${System.nanoTime()}",
            source = "USER",
            defaultRuleKey = null,
            name = routeRuleName(matchType, action),
            description = "",
            enabled = true,
            priority = nextPriority,
            matchType = matchType,
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
            if (showingTelegramConfigs && !selectedServer.available) {
                apiNotice = "Выбранный Telegram-конфиг отключен на ноде"
                return
            }
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                state = LinkState.Connecting
                vpnPermissionLauncher.launch(prepareIntent)
                return
            }
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
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
                        apiNotice = "VPN остановлен"
                    }
                    .onFailure { error ->
                        state = LinkState.Off
                        serviceControlledState = false
                        apiNotice = error.message?.take(90) ?: "Не удалось остановить VPN"
                    }
            }
        }
    }

    BackHandler(enabled = screen == AppScreen.Routing) {
        screen = AppScreen.Settings
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(if (darkRoom) BurgundyDeep else Paper)
    ) {
        val scale = min(maxWidth.value / 412f, maxHeight.value / 892f).coerceIn(0.92f, 1.38f)
        val scaledWidth = maxWidth / scale
        val scaledHeight = maxHeight / scale

        Box(
            modifier = Modifier
                .size(width = scaledWidth, height = scaledHeight)
                .align(Alignment.Center)
                .pointerInput(screen) {
                    var dragX = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragX = 0f },
                        onHorizontalDrag = { _, dragAmount -> dragX += dragAmount },
                        onDragEnd = {
                            if (abs(dragX) > 70f) {
                                screen = screen.swipeTarget(if (dragX < 0f) 1 else -1)
                            }
                            dragX = 0f
                        },
                        onDragCancel = { dragX = 0f }
                    )
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
        ) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    val forward = targetState.screenOrder() > initialState.screenOrder()
                    slideInHorizontally(
                        animationSpec = tween(420)
                    ) { width -> if (forward) width else -width } togetherWith
                        slideOutHorizontally(
                            animationSpec = tween(420)
                        ) { width -> if (forward) -width else width }
                },
                label = "screen-change"
            ) { current ->
                when (current) {
                    AppScreen.Home -> HomeScreen(
                        linkState = state,
                        server = selectedServer,
                        apiNotice = apiNotice,
                        trafficUsedBytes = quotaUsedBytes(),
                        trafficTotalBytes = quotaTotalBytes(),
                        nightTheme = darkRoom,
                        onToggle = ::cycleConnection,
                        onServers = { screen = AppScreen.Servers }
                    )

                    AppScreen.Servers -> ServersScreen(
                        servers = serverNodes,
                        selectedId = selectedServerId,
                        nightTheme = darkRoom,
                        telegramMode = showingTelegramConfigs,
                        onSelect = { selectedServerId = it }
                    )

                    AppScreen.Settings -> SettingsScreen(
                        dnsCheck = dnsCheck,
                        autoConnect = autoConnect,
                        killSwitch = killSwitch,
                        darkRoom = darkRoom,
                        notices = notices,
                        routeRulesCount = routingDraft.routeRules.count { it.enabled },
                        telegramAccount = telegramAccount,
                        telegramStatus = telegramNotice,
                        onDns = { dnsCheck = !dnsCheck },
                        onAuto = { autoConnect = !autoConnect },
                        onKill = { killSwitch = !killSwitch },
                        onDark = { darkRoom = !darkRoom },
                        onNotices = { notices = !notices },
                        onRouting = { screen = AppScreen.Routing },
                        onTelegramLogin = ::openTelegramLogin
                    )

                    AppScreen.Routing -> RoutingScreen(
                        policy = routingDraft,
                        notice = routingNotice,
                        hasUnsyncedChanges = hasRoutingDraftChanges(),
                        nightTheme = darkRoom,
                        onBack = { screen = AppScreen.Settings },
                        onSync = ::syncRoutingPolicy,
                        onToggleDefaultRoute = ::toggleDefaultRoute,
                        onToggleRule = ::toggleRouteRule,
                        onCycleRuleAction = ::cycleRouteRuleAction,
                        onAddRule = ::addRoutingRule,
                        onDeleteRule = ::deleteRouteRule
                    )

                    AppScreen.Speed -> SpeedScreen(nightTheme = darkRoom)
                }
            }

            val offOverlayAlpha = when (state) {
                LinkState.On, LinkState.Paused -> 0f
                LinkState.Connecting -> if (screen == AppScreen.Home) 0f else 0.18f
                LinkState.Off -> if (screen == AppScreen.Home) 0f else 0.28f
            }
            if (offOverlayAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = offOverlayAlpha))
                )
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
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
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
                "APP_PACKAGE" -> value.lowercase()
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
        "IP_CIDR" -> "IP-сети"
        "APP_PACKAGE" -> "Приложения"
        "GEOIP" -> "GeoIP"
        else -> "Домены"
    }
    val route = when (action) {
        "DIRECT" -> "напрямую"
        "BLOCK" -> "блок"
        else -> "через VPN"
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
