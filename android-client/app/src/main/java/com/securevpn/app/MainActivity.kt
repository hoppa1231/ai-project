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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.securevpn.app.data.BackendApi
import com.securevpn.app.data.QuotaStatus
import com.securevpn.app.data.RouteRule
import com.securevpn.app.data.RoutingPolicy
import com.securevpn.app.data.ServerNotification
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

private data class VpnLaunchProfile(
    val vlessUri: String?,
    val clientConfigJson: String?
)

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
    var serverNodes by remember { mutableStateOf(emptyList<ServerNode>()) }
    var selectedServerId by rememberSaveable { mutableStateOf("") }
    var dnsCheck by rememberSaveable { mutableStateOf(true) }
    var cascadeMode by rememberSaveable { mutableStateOf(api.isCascadeModeEnabled()) }
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
    var serverNotification by remember { mutableStateOf<ServerNotification?>(null) }
    var showingTelegramConfigs by remember { mutableStateOf(telegramAccount != null) }
    var userTouchedConnection by remember { mutableStateOf(false) }
    var trafficBytes by rememberSaveable { mutableStateOf(0L) }
    var runtimeQuotaTotalBytes by rememberSaveable { mutableStateOf(0L) }
    var configFailureRetry by rememberSaveable { mutableStateOf(0) }
    var autoConfigRetryCount by rememberSaveable { mutableStateOf(0) }
    var lastConfigFailureError by remember { mutableStateOf<String?>(null) }
    var bootstrapLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notices = granted
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
            node = "TELEGRAM НЕ НАШЕЛ ПРИВЯЗАННЫХ ЗАПИСЕЙ",
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
        if (runtimeState == SingBoxVpnService.STATE_CONFIG_FAILED && !snapshot.error.isNullOrBlank()) {
            lastConfigFailureError = snapshot.error
            apiNotice = "VPN не запустился: ${snapshot.error.take(70)}"
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
                    serverNodes = emptyList()
                    if (!userTouchedConnection && !serviceControlledState) {
                        state = LinkState.Off
                    }
                    apiNotice = "Нет интернета. Проверьте подключение и попробуйте снова"
                    return@launch
                }

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
                        cascadeMode = api.isCascadeModeEnabled()
                        quotaStatus = bootstrap.quota
                        serverNotification = bootstrap.notifications.firstOrNull()
                        if (apiNodes.none { it.id == selectedServerId }) {
                            selectedServerId = apiNodes.firstOrNull()?.id ?: selectedServerId
                        }
                        if (!userTouchedConnection && !serviceControlledState) state = LinkState.Off
                    }
                    .onFailure { error ->
                        serverNodes = emptyList()
                        if (!userTouchedConnection && !serviceControlledState) state = LinkState.Off
                        apiNotice = if (!context.hasInternetConnection()) {
                            "Нет интернета. Проверьте подключение и попробуйте снова"
                        } else {
                            error.message?.take(90) ?: "API недоступен"
                        }
                    }
            } finally {
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
        val webLoginUrl = Uri.parse("${BuildConfig.API_BASE_URL}/auth/telegram/mobile-login")
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
        quotaStatus?.totalBytes?.takeIf { it > 0L }?.let { return it }
        val configBytes = if (showingTelegramConfigs) selectedServer.totalBytes else 0L
        return maxOf(configBytes, runtimeQuotaTotalBytes)
    }

    suspend fun resolveVpnLaunchProfile(forceNew: Boolean = false): VpnLaunchProfile {
        if (showingTelegramConfigs) selectedServer.vlessUri?.takeIf { it.isNotBlank() }?.let {
            return VpnLaunchProfile(vlessUri = it, clientConfigJson = null)
        }
        var forceRotateForRouteMode = false
        if (!forceNew) {
            if (!api.activeConfigMatchesDefaultRouteMode()) {
                api.clearSavedActiveConfig()
                forceRotateForRouteMode = true
            }
            api.activeClientConfigJson()?.takeIf { it.isNotBlank() }?.let {
                return VpnLaunchProfile(vlessUri = api.activeVlessUri(), clientConfigJson = it)
            }
            api.activeVlessUri()?.takeIf { it.isNotBlank() }?.let {
                return VpnLaunchProfile(vlessUri = it, clientConfigJson = null)
            }
        }
        val issued = api.issueVpnConfig(
            region = selectedServer.region.takeIf { it.isNotBlank() },
            exitNodeId = selectedServer.id.substringBefore(":").takeIf { it.isNotBlank() },
            forceRotate = forceNew || forceRotateForRouteMode
        )
        val clientConfigJson = issued.clientConfigJson?.takeIf { it.isNotBlank() }
        val vlessUri = issued.vlessUri?.takeIf { it.isNotBlank() }
        require(clientConfigJson != null || vlessUri != null) { "Сервер не вернул VPN-конфиг" }
        return VpnLaunchProfile(vlessUri = vlessUri, clientConfigJson = clientConfigJson)
    }

    suspend fun startTunnelWithConfig(policy: RoutingPolicy, forceNewConfig: Boolean = false) {
        val launchProfile = resolveVpnLaunchProfile(forceNew = forceNewConfig)
        val tunnel = SingBoxTunnel(context)
        val clientConfigJson = launchProfile.clientConfigJson
        if (clientConfigJson != null) {
            tunnel.startWithClientConfig(
                clientConfigJson,
                policy,
                quotaUsedBytes = quotaUsedBytes(),
                quotaTotalBytes = quotaTotalBytes()
            )
        } else {
            tunnel.start(
                requireNotNull(launchProfile.vlessUri) { "Сервер не вернул VLESS-конфиг" },
                policy,
                quotaUsedBytes = quotaUsedBytes(),
                quotaTotalBytes = quotaTotalBytes()
            )
        }
    }

    LaunchedEffect(configFailureRetry) {
        if (configFailureRetry <= 0) return@LaunchedEffect
        if (autoConfigRetryCount >= 1) {
            state = LinkState.Off
            apiNotice = lastConfigFailureError?.let { "VPN не запустился: ${it.take(70)}" }
                ?: "Новый конфиг тоже не запустился"
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
            apiNotice = "Нет интернета. VPN не запущен"
        } else if (!selectedServer.available || selectedServer.id == "empty") {
            state = LinkState.Off
            serviceControlledState = false
            apiNotice = "Нет доступного VPN-узла. Проверьте подключение"
        } else if (showingTelegramConfigs && !selectedServer.available) {
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

    fun dismissServerNotification(notification: ServerNotification) {
        serverNotification = null
        scope.launch { runCatching { api.markNotificationRead(notification.id) } }
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

    fun toggleCascadeMode() {
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
                    val policy = if (hasRoutingDraftChanges()) routingDraft else api.cachedRoutingPolicy()
                    startTunnelWithConfig(policy, forceNewConfig = true)
                }
                    .onSuccess {
                        state = LinkState.On
                        serviceControlledState = true
                        apiNotice = if (nextEnabled) {
                            "VPN переведен в каскадный режим"
                        } else {
                            "VPN переведен на один узел"
                        }
                    }
                    .onFailure { error ->
                        runCatching { SingBoxTunnel(context).stop() }
                        state = LinkState.Off
                        serviceControlledState = false
                        apiNotice = error.message?.take(90) ?: "Не удалось сменить режим VPN"
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
                apiNotice = if (bootstrapLoading) "Загружаем список VPN-узлов..." else "Нет доступного VPN-узла. Обновляем список..."
                refreshBootstrap()
                return
            }
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

    @Composable
    fun ScreenContent(targetScreen: AppScreen) {
        when (targetScreen) {
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

    BackHandler(enabled = screen == AppScreen.Routing) {
        screen = AppScreen.Settings
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (darkRoom) BurgundyDeep else Paper)
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
            val swipeDirection = if (swipeOffsetPx < 0f) 1 else -1
            val adjacentScreen = screen.swipeTarget(swipeDirection)
            if (swipeOffsetPx != 0f && adjacentScreen != screen) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = swipeOffsetPx + if (swipeDirection > 0) swipeWidthPx else -swipeWidthPx
                        }
                ) {
                    ScreenContent(adjacentScreen)
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = swipeOffsetPx }
            ) {
                ScreenContent(screen)
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
