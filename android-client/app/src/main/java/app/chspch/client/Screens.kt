package app.chspch.client

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.chspch.client.data.RouteRule
import app.chspch.client.data.RoutingPolicy
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
    val isOn = linkState == LinkState.On || linkState == LinkState.Paused
    val isConnecting = linkState == LinkState.Connecting
    val gold = when (linkState) {
        LinkState.On -> Gold
        LinkState.Paused -> GoldDeep
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
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val veryCompactHeight = maxHeight < 650.dp
            val compactHeight = maxHeight < 780.dp
            val switchPanelHeight = when {
                veryCompactHeight -> 190.dp
                compactHeight -> 250.dp
                else -> 330.dp
            }
            val switchWidth = when {
                veryCompactHeight -> 150.dp
                compactHeight -> 180.dp
                else -> 220.dp
            }
            val switchHeight = when {
                veryCompactHeight -> 220.dp
                compactHeight -> 270.dp
                else -> 350.dp
            }
            val sectionGap = if (veryCompactHeight) 3.dp else if (compactHeight) 6.dp else 10.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (veryCompactHeight) 12.dp else 18.dp)
                .padding(
                    top = if (veryCompactHeight) 4.dp else if (compactHeight) 8.dp else 14.dp,
                    bottom = if (veryCompactHeight) 2.dp else 6.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ScreenHeader(
                title = "ВПН\nТРУДЯЩИХСЯ",
                subtitle = when (linkState) {
                    LinkState.Off -> "— связь обесточена —"
                    LinkState.Connecting -> "— устанавливаемъ соединение —"
                    LinkState.Paused -> "— связь поставлена на паузу —"
                    LinkState.On -> "— да здравствуетъ свободный трафикъ! —"
                },
                color = titleColor,
                subtitleColor = textColor.copy(alpha = 0.86f),
                titleSize = if (veryCompactHeight) 22 else if (compactHeight) 26 else 30
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(switchPanelHeight)
                    .padding(top = if (veryCompactHeight) 0.dp else 4.dp),
                contentAlignment = Alignment.Center
            ) {
                VpnKnifeSwitch(
                    state = linkState,
                    onToggle = onToggle,
                    modifier = Modifier.size(width = switchWidth, height = switchHeight)
                )
            }

            if (!veryCompactHeight) {
                Text(
                    text = when (linkState) {
                        LinkState.Off -> "↑ нажмите кнопку или потяните вверхъ ↑"
                        LinkState.Connecting -> "∙∙∙ замыкаемъ контакты ∙∙∙"
                        LinkState.Paused -> "↓ нажмите кнопку, чтобы остановить связь ↓"
                        LinkState.On -> "↓ нажмите кнопку или потяните внизъ ↓"
                    },
                    color = if (isConnecting) OrangeSignal else bone.copy(alpha = 0.78f),
                    fontFamily = Playfair,
                    fontStyle = FontStyle.Italic,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(sectionGap))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (veryCompactHeight) 54.dp else if (compactHeight) 64.dp else 76.dp)
            ) {
                StatusPanel(linkState = linkState, gold = gold, bone = bone, nightTheme = nightTheme)
            }
            Spacer(Modifier.height(sectionGap))
            ConnectivityPanel(linkState, nightTheme)
            Spacer(Modifier.height(sectionGap))
            ServerTicket(
                server = server,
                enabled = true,
                onClick = onServers
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (veryCompactHeight) 20.dp else 28.dp),
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
}

fun formatTrafficBytes(bytes: Long): String {
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

@Composable
fun ServersScreen(
    servers: List<ServerNode>,
    selectedId: String,
    nightTheme: Boolean,
    telegramMode: Boolean,
    onBack: () -> Unit,
    onSelect: (String) -> Unit
) {
    var stampingId by remember { mutableStateOf<String?>(null) }
    val selected = servers.firstOrNull { it.id == selectedId } ?: servers.firstOrNull()
    val background = if (nightTheme) BurgundyDark else Paper
    val primary = if (nightTheme) Gold else Burgundy
    val bodyText = if (nightTheme) Bone else InkSoft
    val title = if (telegramMode) "ВАШИ КОНФИГИ" else "УЗЛЫ СВЯЗИ"
    val subtitle = if (telegramMode) "выберите пропускъ Телеграм — включимъ его" else "выберите станцию — поставимъ штампъ"
    val sectionTitle = if (telegramMode) "§ КОНФИГИ ТЕЛЕГРАМ (${servers.size})" else "§ ВСЕ СТАНЦИИ (${servers.size})"

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
                    title = title,
                    subtitle = subtitle,
                    color = primary,
                    subtitleColor = bodyText,
                    titleSize = 25
                )
            }

            Box(Modifier.height(2.dp).fillMaxWidth().background(if (nightTheme) GoldDeep else Burgundy))

            selected?.let { CurrentServerCard(it, nightTheme = nightTheme) }
            SearchBox(nightTheme = nightTheme, telegramMode = telegramMode)

            Text(
                text = sectionTitle,
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
                if (servers.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(148.dp)
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (telegramMode) {
                                    "для привязанного Телеграм пока нет конфиговъ"
                                } else {
                                    "станции пока не загружены"
                                },
                                color = bodyText,
                                fontFamily = Playfair,
                                fontStyle = FontStyle.Italic,
                                fontSize = 13.sp,
                                lineHeight = 15.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
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

            Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = primary, contentColor = if (nightTheme) BurgundyDeep else BoneLight)) {
                Text("НАЗАД", fontFamily = Russo)
            }

            Text(
                if (telegramMode) "конфиги свѣрены по привязанному Телеграм ★ 1949" else "списокъ узловъ свѣренъ съ Главсвязью ★ 1949",
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
    cascadeMode: Boolean,
    autoConnect: Boolean,
    killSwitch: Boolean,
    darkRoom: Boolean,
    notices: Boolean,
    routeRulesCount: Int,
    telegramAccount: app.chspch.client.data.TelegramAccount?,
    telegramStatus: String?,
    onDns: () -> Unit,
    onCascade: () -> Unit,
    onAuto: () -> Unit,
    onKill: () -> Unit,
    onDark: () -> Unit,
    onNotices: () -> Unit,
    onRouting: () -> Unit,
    onTelegramLogin: () -> Unit,
    onCheckUpdates: () -> Unit = {}
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
                    subtitle = "параграфы для строгаго исполнения",
                    color = primary,
                    subtitleColor = soft,
                    titleSize = 28
                )
            }

            Box(Modifier.height(3.dp).fillMaxWidth().background(line))
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
                    .padding(top = 10.dp, bottom = 76.dp)
            ) {
                item {
                    UserPermitCard(
                        primary = primary,
                        text = text,
                        border = line,
                        panel = panel,
                        telegramAccount = telegramAccount,
                        telegramStatus = telegramStatus,
                        onTelegramLogin = onTelegramLogin
                    )
                }
                item {
                    SettingsSection("СВЯЗЬ", primary = primary, border = line, panel = panel) {
                        SettingsRow("1.1", "Шифръ канала", "АЕС-256", text = text, soft = soft)
                        SettingsRow("1.2", "Протоколъ", "VLESS", text = text, soft = soft)
                        SettingsRow("1.3", "Проверка имён сайтов", if (dnsCheck) "включено" else "выключено", checked = dnsCheck, onToggle = onDns, text = text, soft = soft, night = darkRoom)
                        SettingsRow("1.4", "Каскадный режимъ · БЕТА", if (cascadeMode) "включено · черезъ два узла" else "выключено · один узелъ", checked = cascadeMode, onToggle = onCascade, text = text, soft = soft, night = darkRoom)
                    }
                }
                item {
                    SettingsSection("ДИСЦИПЛИНА", primary = primary, border = line, panel = panel) {
                        SettingsRow("2.1", "Авто-подключение", if (autoConnect) "включено · при запуске устройства" else "выключено · при запуске устройства", checked = autoConnect, onToggle = onAuto, text = text, soft = soft, night = darkRoom)
                        SettingsRow("2.2", "Стопъ-кранъ", if (killSwitch) "включено · прерывать связь при обрывѣ" else "выключено · прерывать связь при обрывѣ", checked = killSwitch, onToggle = onKill, text = text, soft = soft, night = darkRoom)
                        SettingsRow("2.3", "Маршрутизация", "$routeRulesCount правилъ активно", onToggle = onRouting, text = text, soft = soft)
                    }
                }
                item {
                    SettingsSection("ВНѢШНИЙ ВИДЪ", primary = primary, border = line, panel = panel) {
                        SettingsRow("3.1", "Ночная смена", if (darkRoom) "включено · ночной видъ" else "выключено · дневной видъ", checked = darkRoom, onToggle = onDark, text = text, soft = soft, night = darkRoom)
                        SettingsRow("3.2", "Уведомления", if (notices) "карточка трафика в шторке" else "без карточки трафика", checked = notices, onToggle = onNotices, text = text, soft = soft, night = darkRoom)
                        SettingsRow("3.3", "Языкъ интерфейса", "Русский", text = text, soft = soft)
                        SettingsRow("3.4", "Обновление приложения", "Версия ${BuildConfig.VERSION_NAME} · проверить", onToggle = onCheckUpdates, text = text, soft = soft)
                    }
                }
                item {
                    Text(
                        "◆ ★ ◆",
                        color = primary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun RoutingScreen(
    policy: RoutingPolicy,
    canEdit: Boolean,
    onTelegramLogin: () -> Unit,
    notice: String?,
    hasUnsyncedChanges: Boolean,
    nightTheme: Boolean,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onToggleRule: (String) -> Unit,
    onAddRule: (matchType: String, action: String, values: String) -> Unit,
    onDeleteRule: (String) -> Unit
) {
    var valuesInput by remember { mutableStateOf("") }
    var addingApps by remember { mutableStateOf(false) }
    val appContext = LocalContext.current
    var installedApps by remember { mutableStateOf(emptyList<InstalledAppOption>()) }
    var appsLoaded by remember { mutableStateOf(false) }
    var appSearch by remember { mutableStateOf("") }
    LaunchedEffect(addingApps) {
        if (addingApps) {
        appsLoaded = false
        installedApps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val pm = appContext.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { InstalledAppOption(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm).toBitmap(96, 96).asImageBitmap()) }
                .filter { it.packageName != appContext.packageName }
                .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        }
        appsLoaded = true
        }
    }
    val primary = if (nightTheme) Gold else Burgundy
    val text = if (nightTheme) BoneLight else Ink
    val soft = if (nightTheme) Bone else InkSoft
    val line = if (nightTheme) GoldDeep else Ink
    val panel = if (nightTheme) Color(0x66200808) else Color.White.copy(alpha = 0.35f)
    if (addingApps) AlertDialog(
        onDismissRequest = { addingApps = false },
        containerColor = if (nightTheme) BurgundyDark else Paper,
        titleContentColor = primary,
        title = { Text("Приложения через ВПН", fontFamily = Russo, fontSize = 19.sp) },
        text = {
            Column {
                OutlinedTextField(value = appSearch, onValueChange = { appSearch = it }, label = { Text("Поиск приложения") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                val visibleApps = installedApps.filter { it.label.contains(appSearch, true) || it.packageName.contains(appSearch, true) }
                LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!appsLoaded) item { Text("Загружаем приложения…", color = text) }
                    else if (visibleApps.isEmpty()) item { Text("Приложения не найдены", color = text) }
                    items(visibleApps, key = { it.packageName }) { app ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(panel)
                            .clickable { onAddRule("APP_PACKAGE", "VPN", app.packageName); addingApps = false }
                            .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, color = text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.packageName, color = soft, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("+", color = primary, fontSize = 22.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { addingApps = false }) { Text("Закрыть", color = primary) } }
    )
    val ruleIndices = remember(policy.routeRules) { policy.routeRules.withIndex().associate { it.value.id to it.index + 1 } }

    PosterFrame(background = if (nightTheme) BurgundyDark else Paper, dark = nightTheme) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                ScreenHeader(
                    title = "МАРШРУТЫ\nТРАФИКА",
                    subtitle = "правила прохождения пакетов",
                    color = primary,
                    subtitleColor = soft,
                    titleSize = 28
                )
            }

            Box(Modifier.height(3.dp).fillMaxWidth().background(line))
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
                    .padding(top = 10.dp, bottom = 76.dp)
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onBack) { Text("НАЗАД", color = primary, fontFamily = Russo) }
                        if (!canEdit) Button(onClick = onTelegramLogin) { Text("Войти через Телеграм") }
                        else if (hasUnsyncedChanges) Button(onClick = onSync) { Text("Сохранить") }
                    }
                    notice?.let { Text(it, color = soft, modifier = Modifier.padding(8.dp)) }
                }

                if (canEdit) item {
                    SettingsSection("ДОБАВИТЬ ПРАВИЛО", primary = primary, border = line, panel = panel) {
                        Button(onClick = { appSearch = ""; addingApps = true },
                            modifier = Modifier.fillMaxWidth().padding(10.dp).heightIn(min = 52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = primary, contentColor = if (nightTheme) BurgundyDeep else BoneLight)) {
                            Text("+  ДОБАВИТЬ ПРИЛОЖЕНИЕ", fontFamily = Russo, fontSize = 13.sp)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RouteValuesInput(
                                value = valuesInput,
                                onValueChange = { valuesInput = it },
                                label = "Сайт или адрес",
                                primary = primary,
                                text = text,
                                soft = soft,
                                nightTheme = nightTheme,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .height(64.dp)
                                    .width(52.dp)
                                    .border(1.dp, primary)
                                    .clickable {
                                        onAddRule("DOMAIN_SUFFIX", "VPN", valuesInput)
                                        valuesInput = ""
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("+", color = primary, fontFamily = Russo, fontSize = 26.sp)
                            }
                        }
                    }
                }

                item {
                    SettingsSection("ПРАВИЛА", primary = primary, border = line, panel = panel) {
                        if (policy.routeRules.isEmpty()) {
                            SettingsRow("1.0", "Правилъ нетъ", "добавьте сайт или приложение", text = text, soft = soft)
                        }
                    }
                }
                items(policy.routeRules, key = { "${it.source}:${it.defaultRuleKey}:${it.id}" }) { rule ->
                    RouteRuleRow(
                        rule = if (canEdit && rule.source != "DEFAULT") rule else rule.copy(editable = false),
                        index = ruleIndices[rule.id] ?: 1,
                        primary = primary,
                        text = text,
                        soft = soft,
                        nightTheme = nightTheme,
                        onToggleRule = onToggleRule,
                        onDeleteRule = onDeleteRule
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteValuesInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    primary: Color,
    text: Color,
    soft: Color,
    nightTheme: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(64.dp)
            .border(1.dp, primary)
            .background(if (nightTheme) Color(0x33000000) else Color.White.copy(alpha = 0.24f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        if (value.isBlank()) {
            Text(
                label,
                color = soft.copy(alpha = 0.72f),
                fontFamily = Playfair,
                fontStyle = FontStyle.Italic,
                fontSize = 10.sp,
                lineHeight = 11.sp
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(
                color = text,
                fontFamily = PtSans,
                fontSize = 12.sp,
                lineHeight = 14.sp
            ),
            maxLines = 4,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun RouteRuleRow(
    rule: RouteRule,
    index: Int,
    primary: Color,
    text: Color,
    soft: Color,
    nightTheme: Boolean,
    onToggleRule: (String) -> Unit,
    onDeleteRule: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val subtitle = listOf(
        routeMatchLabel(rule.matchType),
        routeValuesPreview(rule),
        routeActionLabel(rule.action)
    ).filter { it.isNotBlank() }.joinToString(" · ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, primary.copy(alpha = 0.7f))
            .background(if (nightTheme) Color(0x33000000) else Color.White.copy(alpha = 0.22f))
    ) {
        SettingsRow(
            index = "1.$index",
            title = russianDisplayName(rule.name),
            subtitle = subtitle,
            checked = if (rule.editable) rule.enabled else null,
            onToggle = if (rule.editable) ({ onToggleRule(rule.id) }) else null,
            text = text,
            soft = soft,
            night = nightTheme
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (rule.source == "DEFAULT") "серверное правило" else "моё правило",
                color = soft,
                fontFamily = Playfair,
                fontStyle = FontStyle.Italic,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (expanded) "СВЕРНУТЬ" else "СОСТАВ",
                color = primary,
                fontFamily = Russo,
                fontSize = 10.sp,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
            Text(
                routeActionLabel(rule.action),
                color = primary,
                fontFamily = Russo,
                fontSize = 10.sp,
                modifier = Modifier

                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
            if (rule.source == "USER" && rule.editable) {
                Text(
                    "УДАЛИТЬ",
                    color = OrangeSignal,
                    fontFamily = Russo,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clickable { onDeleteRule(rule.id) }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
        if (expanded) {
            RouteRuleDetails(rule = rule, text = text, soft = soft)
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun RouteRuleDetails(
    rule: RouteRule,
    text: Color,
    soft: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .padding(bottom = 10.dp)
    ) {
        Text(
            routeValuesForDisplay(rule).joinToString("\n"),
            color = text,
            fontFamily = PtSans,
            fontSize = 11.sp,
            lineHeight = 14.sp
        )
        if (rule.description.isNotBlank()) {
            Text(
                russianDisplayName(rule.description),
                color = soft,
                fontFamily = Playfair,
                fontStyle = FontStyle.Italic,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

private fun routeActionLabel(action: String): String = when (action) {
    "DIRECT" -> "напрямую"
    "BLOCK" -> "блок"
    else -> "через ВПН"
}

private fun routeMatchLabel(matchType: String): String = when (matchType) {
    "DOMAIN" -> "точный домен"
    "DOMAIN_SUFFIX" -> "домен и поддомены"
    "DOMAIN_KEYWORD" -> "слово домена"
    "GEOIP" -> "география адресов"
    "IP_CIDR" -> "сеть адресов"
    "APP_PACKAGE" -> "приложение"
    else -> "домен и поддомены"
}

private fun routeValuesPreview(rule: RouteRule): String {
    val values = routeValuesForDisplay(rule)
    val preview = values.take(2).joinToString(", ")
    val hidden = values.size - 2
    return if (hidden > 0) "$preview +$hidden" else preview
}

private fun routeValuesForDisplay(rule: RouteRule): List<String> {
    return rule.values.map { value ->
        if (rule.matchType == "GEOIP" && !value.startsWith("geoip-")) "geoip-$value" else value
    }
}

@Composable
fun SpeedScreen(nightTheme: Boolean) {
    val context = LocalContext.current
    val historyStore = remember(context) { SpeedTestHistory(context) }
    var history by remember { mutableStateOf(historyStore.load()) }
    var testState by remember { mutableStateOf(history.firstOrNull()?.uiState() ?: SpeedTestUiState()) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose { testJob?.cancel() }
    }

    val background = if (nightTheme) BurgundyDark else Paper
    val primary = if (nightTheme) Gold else Burgundy
    val text = if (nightTheme) Bone else InkSoft

    PosterFrame(background = background, dark = nightTheme) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
                .padding(top = 12.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ScreenHeader(
                        title = "ЗАМѢРЪ СКОРОСТИ",
                        subtitle = "контроль качества — законъ производства",
                        color = primary,
                        subtitleColor = text,
                        titleSize = 25,
                        overline = "★ ИЗМѢРИТЕЛЬНЫЙ ПУНКТЪ ★"
                    )

                    Spacer(Modifier.height(8.dp))
                    SpeedReadout(testState, nightTheme)
                    SpeedResultLabel("ТЕКУЩИЙ ЗАМЕР", primary)
                    SpeedMetrics(testState, nightTheme)
                    val previous = if (testState.running || testState.error != null) history.firstOrNull() else history.getOrNull(1)
                    if (previous != null) {
                        Spacer(Modifier.height(12.dp))
                        SpeedResultLabel("ПРЕДЫДУЩИЙ ЗАМЕР", primary)
                        SpeedMetrics(previous.uiState(), nightTheme)
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
                                        val completed = SavedSpeedTest(measuredAt = System.currentTimeMillis(),
                                            averageMbps = result.averageMbps, peakMbps = result.peakMbps,
                                            pingMs = testState.pingMs, downloadedBytes = result.downloadedBytes)
                                        if (completed.isValid()) {
                                            history = (listOf(completed) + history).take(SpeedHistoryCodec.MAX_RESULTS)
                                            historyStore.save(history)
                                        }
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
                    Spacer(Modifier.height(16.dp))
                }
            }

        }
    }
}

@Composable
private fun SpeedResultLabel(label: String, color: Color) {
    Text(label, color = color, fontFamily = Russo, fontSize = 12.sp, letterSpacing = 1.sp)
}

@Composable
private fun SpeedReadout(state: SpeedTestUiState, nightTheme: Boolean) {
    val rawSpeed = if (state.running) state.currentMbps else state.averageMbps
    val displaySpeed by animateFloatAsState(rawSpeed, tween(160), label = "speed-value")
    val subtext = if (nightTheme) Bone else Burgundy
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SpeedGauge(progress = speedToGaugeProgress(displaySpeed), modifier = Modifier.size(250.dp))
        Spacer(Modifier.height(4.dp))
        FlipNumber(value = displaySpeed, modifier = Modifier.fillMaxWidth(), subtext = subtext)
        Text("Мб/с", color = subtext, fontFamily = Russo, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun SpeedMetrics(state: SpeedTestUiState, nightTheme: Boolean) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            MetricBox("ОТКЛИКЪ", state.pingMs?.let { "${it}мс" } ?: "—", nightTheme, Modifier.weight(1f))
            MetricBox("СРЕДНЯЯ", "${formatSpeed(state.averageMbps)} Мб/с", nightTheme, Modifier.weight(1f))
            MetricBox("ПРИНЯТО", "${formatDataMb(state.downloadedBytes / 1_000_000f)}мб", nightTheme, Modifier.weight(1f))
        }
}

private data class InstalledAppOption(val label: String, val packageName: String, val icon: ImageBitmap)
