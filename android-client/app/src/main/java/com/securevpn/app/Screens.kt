package com.securevpn.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    linkState: LinkState,
    server: ServerNode,
    apiNotice: String?,
    nightTheme: Boolean,
    onToggle: () -> Unit,
    onServers: () -> Unit
) {
    val isOff = linkState == LinkState.Off
    val isOn = linkState == LinkState.On
    val isConnecting = linkState == LinkState.Connecting
    val gold = when (linkState) {
        LinkState.On -> Gold
        LinkState.Connecting -> OrangeSignal
        LinkState.Off -> Gold.copy(alpha = 0.45f)
    }
    val bone = if (isOff) Bone.copy(alpha = 0.55f) else BoneLight
    val background = when {
        !nightTheme -> Paper
        isOn -> BurgundyDark
        else -> Color(0xFF1A0A08)
    }
    val titleColor = if (nightTheme) gold else Burgundy
    val textColor = if (nightTheme) bone else InkSoft

    PosterFrame(background = background, dark = nightTheme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp)
                .padding(top = 14.dp, bottom = 76.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ScreenHeader(
                title = "ВПН\nТРУДЯЩИХСЯ",
                subtitle = when (linkState) {
                    LinkState.Off -> "— связь обесточена —"
                    LinkState.Connecting -> "— устанавливаемъ соединеніе —"
                    LinkState.On -> "— да здравствуетъ свободный трафикъ! —"
                },
                color = titleColor,
                subtitleColor = textColor.copy(alpha = 0.86f),
                titleSize = 30
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(390.dp)
                    .padding(top = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                VpnKnifeSwitch(
                    state = linkState,
                    onToggle = onToggle,
                    modifier = Modifier.size(width = 230.dp, height = 366.dp)
                )
            }

            Text(
                text = when (linkState) {
                    LinkState.Off -> "↑ нажмите кнопку или потяните вверхъ ↑"
                    LinkState.Connecting -> "∙∙∙ замыкаемъ контакты ∙∙∙"
                    LinkState.On -> "↓ нажмите кнопку или потяните внизъ ↓"
                },
                color = if (isConnecting) OrangeSignal else bone.copy(alpha = 0.78f),
                fontFamily = Playfair,
                fontStyle = FontStyle.Italic,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
            ) {
                StatusPanel(linkState = linkState, gold = gold, bone = bone, nightTheme = nightTheme)
            }
            Spacer(Modifier.height(12.dp))
            ServerTicket(
                server = server,
                enabled = !isOff,
                onClick = onServers
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    apiNotice.orEmpty(),
                    color = if (nightTheme) GoldDeep else InkSoft,
                    fontFamily = Playfair,
                    fontStyle = FontStyle.Italic,
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun ServersScreen(
    servers: List<ServerNode>,
    selectedId: String,
    nightTheme: Boolean,
    onSelect: (String) -> Unit
) {
    var stampingId by remember { mutableStateOf<String?>(null) }
    val selected = servers.firstOrNull { it.id == selectedId } ?: servers.first()
    val background = if (nightTheme) BurgundyDark else Paper
    val primary = if (nightTheme) Gold else Burgundy
    val bodyText = if (nightTheme) Bone else InkSoft

    LaunchedEffect(stampingId) {
        val id = stampingId ?: return@LaunchedEffect
        delay(680)
        onSelect(id)
        delay(120)
        stampingId = null
    }

    PosterFrame(background = background, dark = nightTheme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 76.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 0.dp, color = Color.Transparent)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ScreenHeader(
                    title = "УЗЛЫ СВЯЗИ",
                    subtitle = "выберите станцію — поставимъ штампъ",
                    color = primary,
                    subtitleColor = bodyText,
                    titleSize = 25
                )
            }

            Box(Modifier.height(2.dp).fillMaxWidth().background(if (nightTheme) GoldDeep else Burgundy))

            CurrentServerCard(selected, nightTheme = nightTheme)
            SearchBox(nightTheme = nightTheme)

            Text(
                text = "§ ВСЕ СТАНЦІИ (${servers.size})",
                color = GoldDeep,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 6.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .weight(1f)
                    .border(1.dp, GoldDeep)
                    .background(if (nightTheme) Color(0x4D000000) else Color(0x22FFFFFF))
            ) {
                items(servers) { server ->
                    ServerRow(
                        server = server,
                        selected = server.id == selectedId,
                        stamping = server.id == stampingId,
                        nightTheme = nightTheme,
                        onClick = { stampingId = server.id }
                    )
                }
            }

            Text(
                "списокъ узловъ свѣренъ съ Главсвязью ★ 1949",
                color = GoldDeep.copy(alpha = 0.72f),
                fontFamily = Playfair,
                fontStyle = FontStyle.Italic,
                fontSize = 9.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 8.dp)
            )
        }
    }
}

@Composable
fun SettingsScreen(
    dnsCheck: Boolean,
    autoConnect: Boolean,
    killSwitch: Boolean,
    darkRoom: Boolean,
    notices: Boolean,
    telegramStatus: String?,
    onDns: () -> Unit,
    onAuto: () -> Unit,
    onKill: () -> Unit,
    onDark: () -> Unit,
    onNotices: () -> Unit,
    onTelegramLogin: () -> Unit
) {
    val primary = if (darkRoom) Gold else Burgundy
    val text = if (darkRoom) BoneLight else Ink
    val soft = if (darkRoom) Bone else InkSoft
    val line = if (darkRoom) GoldDeep else Ink
    val panel = if (darkRoom) Color(0x66200808) else Color.White.copy(alpha = 0.35f)

    PosterFrame(background = if (darkRoom) BurgundyDark else Paper, dark = darkRoom) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                ScreenHeader(
                    title = "УСТАВЪ\nПОЛЬЗОВАТЕЛЯ",
                    subtitle = "параграфы для строгаго исполненія",
                    color = primary,
                    subtitleColor = soft,
                    titleSize = 28
                )
            }

            Box(Modifier.height(3.dp).fillMaxWidth().background(line))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
                    .padding(top = 10.dp, bottom = 76.dp)
            ) {
                UserPermitCard(
                    primary = primary,
                    text = text,
                    border = line,
                    panel = panel,
                    telegramStatus = telegramStatus,
                    onTelegramLogin = onTelegramLogin
                )
                SettingsSection("СВЯЗЬ", primary = primary, border = line, panel = panel) {
                    SettingsRow("1.1", "Шифръ канала", "AES-256", text = text, soft = soft)
                    SettingsRow("1.2", "Протоколъ", "VLESS", text = text, soft = soft)
                    SettingsRow("1.3", "DNS-проверка", if (dnsCheck) "включено" else "выключено", checked = dnsCheck, onToggle = onDns, text = text, soft = soft, night = darkRoom)
                }
                SettingsSection("ДИСЦИПЛИНА", primary = primary, border = line, panel = panel) {
                    SettingsRow("2.1", "Авто-подключение", if (autoConnect) "включено · при запуске устройства" else "выключено · при запуске устройства", checked = autoConnect, onToggle = onAuto, text = text, soft = soft, night = darkRoom)
                    SettingsRow("2.2", "Стопъ-кранъ", if (killSwitch) "включено · прерывать связь при обрывѣ" else "выключено · прерывать связь при обрывѣ", checked = killSwitch, onToggle = onKill, text = text, soft = soft, night = darkRoom)
                    SettingsRow("2.3", "Раздельный туннель", "3 приложенія", text = text, soft = soft)
                }
                SettingsSection("ВНѢШНІЙ ВИДЪ", primary = primary, border = line, panel = panel) {
                    SettingsRow("3.1", "Ночная смена", if (darkRoom) "включено · ночной видъ" else "выключено · дневной видъ", checked = darkRoom, onToggle = onDark, text = text, soft = soft, night = darkRoom)
                    SettingsRow("3.2", "Уведомления", if (notices) "включено · только важныя" else "выключено · только важныя", checked = notices, onToggle = onNotices, text = text, soft = soft, night = darkRoom)
                    SettingsRow("3.3", "Языкъ интерфейса", "Русский", text = text, soft = soft)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "◆ ★ ◆",
                    color = primary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun SpeedScreen(nightTheme: Boolean) {
    var testState by remember { mutableStateOf(SpeedTestUiState()) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose { testJob?.cancel() }
    }

    val rawDisplaySpeed = testState.currentMbps.takeIf { testState.running } ?: testState.averageMbps
    val displaySpeed by animateFloatAsState(
        targetValue = rawDisplaySpeed,
        animationSpec = tween(160),
        label = "speed-value"
    )
    val arc = speedToGaugeProgress(displaySpeed)
    val ping = testState.pingMs?.let { "${it}мс" } ?: "—"
    val downloadedMb = testState.downloadedBytes / 1_000_000f

    val background = if (nightTheme) BurgundyDark else Paper
    val primary = if (nightTheme) Gold else Burgundy
    val text = if (nightTheme) Bone else InkSoft
    val subtext = if (nightTheme) Bone else Burgundy

    PosterFrame(background = background, dark = nightTheme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
                .padding(top = 12.dp, bottom = 76.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ScreenHeader(
                title = "ЗАМѢРЪ СКОРОСТИ",
                subtitle = "контроль качества — законъ производства",
                color = primary,
                subtitleColor = text,
                titleSize = 25,
                overline = "★ ИЗМѢРИТЕЛЬНЫЙ ПУНКТЪ ★"
            )

            Spacer(Modifier.height(8.dp))
            SpeedGauge(progress = arc, modifier = Modifier.size(250.dp))
            Spacer(Modifier.height(4.dp))
            FlipNumber(value = displaySpeed, modifier = Modifier.fillMaxWidth(), subtext = subtext)
            Text("МБИТ/С", color = subtext, fontFamily = Russo, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                MetricBox("ОТКЛИКЪ", ping, nightTheme, Modifier.weight(1f))
                MetricBox("СРЕДНЯЯ", "${formatSpeed(testState.averageMbps)}мб", nightTheme, Modifier.weight(1f))
                MetricBox("ПРИНЯТО", "${formatDataMb(downloadedMb)}мб", nightTheme, Modifier.weight(1f))
            }
            testState.error?.let { message ->
                Spacer(Modifier.height(7.dp))
                Text(
                    message,
                    color = OrangeSignal,
                    fontFamily = PtSans,
                    fontSize = 11.sp,
                    lineHeight = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .border(2.dp, primary)
                    .background(if (testState.running) Color.Transparent else if (nightTheme) Burgundy else Color.White.copy(alpha = 0.38f))
                    .clickable(enabled = !testState.running) {
                        testJob?.cancel()
                        testState = SpeedTestUiState(running = true, status = "идётъ измерение")
                        testJob = scope.launch {
                            try {
                                val result = measureInternetSpeed(
                                    onPing = { pingMs ->
                                        testState = testState.copy(pingMs = pingMs)
                                    },
                                    onSample = { current, average, peak, bytes ->
                                        testState = testState.copy(
                                            currentMbps = current,
                                            averageMbps = average,
                                            peakMbps = peak,
                                            downloadedBytes = bytes,
                                            status = "идётъ измерение",
                                            error = null
                                        )
                                    }
                                )
                                testState = testState.copy(
                                    running = false,
                                    currentMbps = result.averageMbps,
                                    averageMbps = result.averageMbps,
                                    peakMbps = result.peakMbps,
                                    downloadedBytes = result.downloadedBytes,
                                    status = "поверка завершена",
                                    error = null
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                testState = testState.copy(
                                    running = false,
                                    currentMbps = 0f,
                                    status = "сбой поверки",
                                    error = error.message?.take(80) ?: "Не удалось измерить скорость"
                                )
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (testState.running) "∙∙∙ ИДЁТЪ ИЗМЕРЕНИЕ ∙∙∙" else "▶ НАЧАТЬ ЗАМЕРЪ",
                    color = primary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp,
                    fontFamily = Russo
                )
            }
            Text(
                testState.status,
                color = text,
                fontFamily = PtSans,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.weight(1f))
        }
    }
}
