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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
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
import com.securevpn.app.data.RouteRule
import com.securevpn.app.data.RoutingPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    linkState: LinkState,
    server: ServerNode,
    apiNotice: String?,
    trafficUsedBytes: Long,
    trafficTotalBytes: Long,
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
                    LinkState.Paused -> "— связь поставлена на паузу —"
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

            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
            ) {
                StatusPanel(linkState = linkState, gold = gold, bone = bone, nightTheme = nightTheme)
            }
            Spacer(Modifier.height(12.dp))
            TrafficQuotaBar(
                usedBytes = trafficUsedBytes,
                totalBytes = trafficTotalBytes,
                active = linkState != LinkState.Off,
                nightTheme = nightTheme
            )
            Spacer(Modifier.height(8.dp))
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
private fun TrafficQuotaBar(
    usedBytes: Long,
    totalBytes: Long,
    active: Boolean,
    nightTheme: Boolean
) {
    val progress = if (totalBytes <= 0L) 1f else (usedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    val label = if (totalBytes <= 0L) {
        "${formatTrafficBytes(usedBytes)} / ∞"
    } else {
        "${formatTrafficBytes(usedBytes)} / ${formatTrafficBytes(totalBytes)}"
    }
    val border = if (nightTheme) GoldDeep else Burgundy
    val text = if (nightTheme) BoneLight else InkSoft
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, border.copy(alpha = if (active) 0.85f else 0.45f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "ТРАФИК",
                color = border,
                fontFamily = PtSans,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 2.sp
            )
            Text(
                label,
                color = text,
                fontFamily = PtSans,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .padding(top = 5.dp),
            color = if (active) Gold else GoldDeep,
            trackColor = if (nightTheme) Color(0xFF3A1A0A) else Bone.copy(alpha = 0.7f)
        )
    }
}

private fun formatTrafficBytes(bytes: Long): String {
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
    onSelect: (String) -> Unit
) {
    var stampingId by remember { mutableStateOf<String?>(null) }
    val selected = servers.firstOrNull { it.id == selectedId } ?: servers.firstOrNull()
    val background = if (nightTheme) BurgundyDark else Paper
    val primary = if (nightTheme) Gold else Burgundy
    val bodyText = if (nightTheme) Bone else InkSoft
    val title = if (telegramMode) "ВАШИ КОНФИГИ" else "УЗЛЫ СВЯЗИ"
    val subtitle = if (telegramMode) "выберите пропускъ Telegram — включимъ его" else "выберите станцію — поставимъ штампъ"
    val sectionTitle = if (telegramMode) "§ КОНФИГИ TELEGRAM (${servers.size})" else "§ ВСЕ СТАНЦІИ (${servers.size})"

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
                                    "для привязанного Telegram пока нет конфиговъ"
                                } else {
                                    "станціи пока не загружены"
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

            Text(
                if (telegramMode) "конфиги свѣрены по привязанному Telegram ★ 1949" else "списокъ узловъ свѣренъ съ Главсвязью ★ 1949",
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
    routeRulesCount: Int,
    telegramAccount: com.securevpn.app.data.TelegramAccount?,
    telegramStatus: String?,
    onDns: () -> Unit,
    onAuto: () -> Unit,
    onKill: () -> Unit,
    onDark: () -> Unit,
    onNotices: () -> Unit,
    onRouting: () -> Unit,
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
                        SettingsRow("1.1", "Шифръ канала", "AES-256", text = text, soft = soft)
                        SettingsRow("1.2", "Протоколъ", "VLESS", text = text, soft = soft)
                        SettingsRow("1.3", "DNS-проверка", if (dnsCheck) "включено" else "выключено", checked = dnsCheck, onToggle = onDns, text = text, soft = soft, night = darkRoom)
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
                    SettingsSection("ВНѢШНІЙ ВИДЪ", primary = primary, border = line, panel = panel) {
                        SettingsRow("3.1", "Ночная смена", if (darkRoom) "включено · ночной видъ" else "выключено · дневной видъ", checked = darkRoom, onToggle = onDark, text = text, soft = soft, night = darkRoom)
                        SettingsRow("3.2", "Уведомления", if (notices) "включено · только важныя" else "выключено · только важныя", checked = notices, onToggle = onNotices, text = text, soft = soft, night = darkRoom)
                        SettingsRow("3.3", "Языкъ интерфейса", "Русский", text = text, soft = soft)
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
    notice: String?,
    hasUnsyncedChanges: Boolean,
    nightTheme: Boolean,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onToggleDefaultRoute: () -> Unit,
    onToggleRule: (String) -> Unit,
    onCycleRuleAction: (String) -> Unit,
    onAddRule: (matchType: String, action: String, values: String) -> Unit,
    onDeleteRule: (String) -> Unit
) {
    var valuesInput by remember { mutableStateOf("") }
    var newRuleType by remember { mutableStateOf("DOMAIN_SUFFIX") }
    var newRuleAction by remember { mutableStateOf("DIRECT") }
    val primary = if (nightTheme) Gold else Burgundy
    val text = if (nightTheme) BoneLight else Ink
    val soft = if (nightTheme) Bone else InkSoft
    val line = if (nightTheme) GoldDeep else Ink
    val panel = if (nightTheme) Color(0x66200808) else Color.White.copy(alpha = 0.35f)
    val defaultIsVpn = policy.defaultRoute == "VPN"

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
                    SettingsSection("ОБЩИЙ ХОД", primary = primary, border = line, panel = panel) {
                        SettingsRow("0.1", "По умолчанию", if (defaultIsVpn) "через VPN" else "напрямую", checked = defaultIsVpn, onToggle = onToggleDefaultRoute, text = text, soft = soft, night = nightTheme)
                        SettingsRow(
                            "0.2",
                            "Синхронизация",
                            if (hasUnsyncedChanges) notice ?: "есть локальные правки" else notice ?: "версия ${policy.version}",
                            onToggle = onSync,
                            text = text,
                            soft = soft
                        )
                        SettingsRow("0.3", "Назадъ", "вернуться к настройкам", onToggle = onBack, text = text, soft = soft)
                    }
                }

                item {
                    SettingsSection("ДОБАВИТЬ ПРАВИЛО", primary = primary, border = line, panel = panel) {
                        SettingsRow(
                            "A.1",
                            "Тип правила",
                            routeMatchLabel(newRuleType),
                            onToggle = { newRuleType = nextRouteRuleType(newRuleType) },
                            text = text,
                            soft = soft
                        )
                        SettingsRow(
                            "A.2",
                            "Действие",
                            routeActionLabel(newRuleAction),
                            onToggle = { newRuleAction = nextRouteRuleAction(newRuleAction) },
                            text = text,
                            soft = soft
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RouteValuesInput(
                                value = valuesInput,
                                onValueChange = { valuesInput = it },
                                label = routeInputLabel(newRuleType),
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
                                    .width(86.dp)
                                    .border(1.dp, primary)
                                    .clickable {
                                        onAddRule(newRuleType, newRuleAction, valuesInput)
                                        valuesInput = ""
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("ДОБАВИТЬ", color = primary, fontFamily = Russo, fontSize = 9.sp)
                            }
                        }
                    }
                }

                item {
                    SettingsSection("ПРАВИЛА", primary = primary, border = line, panel = panel) {
                        if (policy.routeRules.isEmpty()) {
                            SettingsRow("1.0", "Правилъ нетъ", "весь трафик идет по общему ходу", text = text, soft = soft)
                        }
                    }
                }
                items(policy.routeRules, key = { "${it.source}:${it.defaultRuleKey}:${it.id}" }) { rule ->
                    RouteRuleRow(
                        rule = rule,
                        index = policy.routeRules.indexOf(rule) + 1,
                        primary = primary,
                        text = text,
                        soft = soft,
                        nightTheme = nightTheme,
                        onToggleRule = onToggleRule,
                        onCycleRuleAction = onCycleRuleAction,
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
    onCycleRuleAction: (String) -> Unit,
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
            title = rule.name,
            subtitle = subtitle,
            checked = rule.enabled,
            onToggle = { onToggleRule(rule.id) },
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
                    .clickable { onCycleRuleAction(rule.id) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
            if (rule.source == "USER") {
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
                rule.description,
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
    else -> "через VPN"
}

private fun routeMatchLabel(matchType: String): String = when (matchType) {
    "DOMAIN" -> "точный домен"
    "DOMAIN_SUFFIX" -> "domain-suffix"
    "DOMAIN_KEYWORD" -> "слово домена"
    "GEOIP" -> "geoip"
    "IP_CIDR" -> "IP-сеть"
    "APP_PACKAGE" -> "приложение"
    else -> "domain-suffix"
}

private fun routeInputLabel(matchType: String): String = when (matchType) {
    "DOMAIN" -> "домены"
    "DOMAIN_KEYWORD" -> "ключевые слова"
    "GEOIP" -> "geoip-ru, geoip-ru-blocked"
    "IP_CIDR" -> "IP CIDR"
    "APP_PACKAGE" -> "пакеты приложений"
    else -> "домены"
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

private fun nextRouteRuleType(current: String): String {
    val types = listOf("DOMAIN_SUFFIX", "DOMAIN_KEYWORD", "DOMAIN", "GEOIP", "IP_CIDR", "APP_PACKAGE")
    return types[(types.indexOf(current).coerceAtLeast(0) + 1) % types.size]
}

private fun nextRouteRuleAction(current: String): String {
    val actions = listOf("DIRECT", "VPN", "BLOCK")
    return actions[(actions.indexOf(current).coerceAtLeast(0) + 1) % actions.size]
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
