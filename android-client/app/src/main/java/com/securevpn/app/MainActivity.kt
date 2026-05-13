package com.securevpn.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.net.Uri
import android.os.Bundle
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
import com.securevpn.app.data.BackendApi
import com.securevpn.app.data.TelegramAppLoginResult
import com.securevpn.app.data.TelegramAuthData
import com.securevpn.app.ui.theme.SecureVpnTheme
import com.securevpn.app.vpn.SingBoxTunnel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.delay
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
    var userTouchedConnection by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val selectedServer = serverNodes.firstOrNull { it.id == selectedServerId } ?: serverNodes.first()
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

    fun refreshBootstrap() {
        scope.launch {
            runCatching { api.bootstrap() }
                .onSuccess { bootstrap ->
                    val apiNodes = bootstrap.nodes.mapIndexed { index, node -> node.toServerNode(index) }
                    if (apiNodes.isNotEmpty()) {
                        serverNodes = apiNodes
                        if (apiNodes.none { it.id == selectedServerId }) {
                            selectedServerId = apiNodes.first().id
                        }
                    }
                    if (!userTouchedConnection) {
                        state = LinkState.Off
                    }
                    apiNotice = null
                }
                .onFailure { error ->
                    if (!userTouchedConnection) {
                        state = LinkState.Off
                    }
                    apiNotice = error.message?.take(90) ?: "API недоступен"
                }
        }
    }

    LaunchedEffect(Unit) {
        refreshBootstrap()
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
            apiNotice = "Telegram уже привязан"
            return
        }
        telegramNotice = "готовим вход через Telegram..."
        scope.launch {
            runCatching { api.startTelegramAppLogin() }
                .onSuccess { login ->
                    telegramNotice = "откройте бота и нажмите Start"
                    val opened = runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(login.telegramAppUrl)))
                        true
                    }.getOrDefault(false)
                    if (!opened) {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(login.telegramWebUrl)))
                        }.onFailure { error ->
                            telegramNotice = error.message?.take(70) ?: "Не удалось открыть Telegram"
                            apiNotice = telegramNotice
                            return@launch
                        }
                    }

                    repeat(75) {
                        delay(2_000)
                        when (val result = runCatching { api.pollTelegramAppLogin(login.challengeId) }.getOrNull()) {
                            is TelegramAppLoginResult.Ready -> {
                                val account = result.account ?: api.telegramAccount()
                                telegramAccount = account
                                telegramNotice = "вход выполнен: ${account?.displayName ?: "Telegram"}"
                                apiNotice = "Telegram-авторизация принята"
                                refreshBootstrap()
                                return@launch
                            }
                            TelegramAppLoginResult.Pending, null -> Unit
                        }
                    }
                    telegramNotice = "Telegram вход истек"
                    apiNotice = telegramNotice
                }
                .onFailure { error ->
                    telegramNotice = error.message?.take(70) ?: "Не удалось начать Telegram вход"
                    apiNotice = telegramNotice
                }
        }
    }

    LaunchedEffect(telegramAuthEvents) {
        telegramAuthEvents.collect(::completeTelegramLogin)
    }

    startConnection = {
        state = LinkState.Connecting
        scope.launch {
            runCatching {
                SingBoxTunnel(context).start(WorkingVlessUri)
            }
                .onSuccess {
                    state = LinkState.On
                    apiNotice = "VPN запущен через рабочую VLESS-ссылку"
                }
                .onFailure { error ->
                    runCatching { SingBoxTunnel(context).stop() }
                    state = LinkState.Off
                    apiNotice = error.message?.take(90) ?: "Не удалось запустить VPN"
                }
        }
    }

    fun cycleConnection() {
        if (state == LinkState.Connecting) return
        userTouchedConnection = true
        if (state == LinkState.Off) {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                state = LinkState.Connecting
                vpnPermissionLauncher.launch(prepareIntent)
                return
            }
            startConnection()
        } else {
            state = LinkState.Connecting
            scope.launch {
                runCatching { SingBoxTunnel(context).stop() }
                    .onSuccess {
                        state = LinkState.Off
                        apiNotice = "VPN остановлен"
                    }
                    .onFailure { error ->
                        state = LinkState.Off
                        apiNotice = error.message?.take(90) ?: "Не удалось остановить VPN"
                    }
            }
        }
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
                        nightTheme = darkRoom,
                        onToggle = ::cycleConnection,
                        onServers = { screen = AppScreen.Servers }
                    )

                    AppScreen.Servers -> ServersScreen(
                        servers = serverNodes,
                        selectedId = selectedServerId,
                        nightTheme = darkRoom,
                        onSelect = { selectedServerId = it }
                    )

                    AppScreen.Settings -> SettingsScreen(
                        dnsCheck = dnsCheck,
                        autoConnect = autoConnect,
                        killSwitch = killSwitch,
                        darkRoom = darkRoom,
                        notices = notices,
                        telegramAccount = telegramAccount,
                        telegramStatus = telegramNotice,
                        onDns = { dnsCheck = !dnsCheck },
                        onAuto = { autoConnect = !autoConnect },
                        onKill = { killSwitch = !killSwitch },
                        onDark = { darkRoom = !darkRoom },
                        onNotices = { notices = !notices },
                        onTelegramLogin = ::openTelegramLogin
                    )

                    AppScreen.Speed -> SpeedScreen(nightTheme = darkRoom)
                }
            }

            val offOverlayAlpha = when (state) {
                LinkState.On -> 0f
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
                current = if (screen == AppScreen.Servers) AppScreen.Home else screen,
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

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun SovietVpnPreview() {
    SecureVpnTheme {
        SovietVpnApp()
    }
}
