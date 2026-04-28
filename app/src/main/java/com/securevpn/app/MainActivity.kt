package com.securevpn.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.securevpn.app.data.BackendApi
import com.securevpn.app.data.QuotaStatus
import com.securevpn.app.data.VpnNode
import com.securevpn.app.ui.theme.AccentBlue
import com.securevpn.app.ui.theme.AccentGreen
import com.securevpn.app.ui.theme.AccentPurple
import com.securevpn.app.ui.theme.AccentWarning
import com.securevpn.app.ui.theme.BackgroundEnd
import com.securevpn.app.ui.theme.BackgroundStart
import com.securevpn.app.ui.theme.CardSurface
import com.securevpn.app.ui.theme.GlassSurface
import com.securevpn.app.ui.theme.SecureVpnTheme
import com.securevpn.app.ui.theme.TextPrimary
import com.securevpn.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SecureVpnTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VpnHomeScreen()
                }
            }
        }
    }
}

@Composable
private fun VpnHomeScreen() {
    val context = LocalContext.current
    val api = remember { BackendApi(context) }
    var connected by rememberSaveable { mutableStateOf(false) }
    var swipeProgress by remember { mutableFloatStateOf(0f) }
    var serverStatus by remember { mutableStateOf("Loading server list...") }
    var connectionStatus by remember { mutableStateOf<String?>(null) }
    var nodes by remember { mutableStateOf<List<VpnNode>>(emptyList()) }
    var selectedNode by remember { mutableStateOf<VpnNode?>(null) }
    var quota by remember { mutableStateOf<QuotaStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching { api.bootstrap() }
            .onSuccess { bootstrap ->
                nodes = bootstrap.nodes
                selectedNode = bootstrap.nodes.firstOrNull()
                quota = bootstrap.quota
                connected = bootstrap.activeConfigId != null
                serverStatus = bootstrap.nodes.firstOrNull()?.let { "${it.region} | ${it.health}" }
                    ?: "No VPN nodes configured"
            }
            .onFailure {
                serverStatus = "API error: ${it.message.orEmpty()}"
                connectionStatus = "Could not load API state"
            }
    }

    val progressAnimated by animateFloatAsState(
        targetValue = swipeProgress,
        animationSpec = tween(120),
        label = "swipe-progress"
    )

    val lockColor by animateColorAsState(
        targetValue = if (connected) AccentGreen else lerp(TextSecondary, AccentGreen, progressAnimated),
        animationSpec = tween(220),
        label = "lock-color"
    )

    val panelBackground = Brush.verticalGradient(
        colors = listOf(
            BackgroundStart,
            BackgroundEnd
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF191B21))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(26.dp))
                .background(panelBackground)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                if (connected) AccentGreen.copy(alpha = 0.24f) else AccentPurple.copy(alpha = 0.22f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.5f, size.height * 0.48f),
                            radius = size.minDimension * 0.55f
                        )
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                AccentBlue.copy(alpha = 0.20f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.75f, size.height * 0.65f),
                            radius = size.minDimension * 0.58f
                        )
                    )
                }
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            HeaderRow(connected = connected)

            AnimatedVisibility(visible = connected) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    TrafficCard(quota = quota)
                    Spacer(modifier = Modifier.height(12.dp))
                    StatsRow()
                }
            }

            Spacer(modifier = Modifier.height(if (connected) 16.dp else 32.dp))

            Text(
                text = if (connected) "Tap to disconnect" else "Hold and swipe up to connect",
                color = TextSecondary,
                fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            LockControl(
                connected = connected,
                progress = progressAnimated,
                lockColor = lockColor,
                enabled = !busy,
                onConnect = {
                    if (!busy) {
                        scope.launch {
                            busy = true
                            connectionStatus = "Requesting VPN config..."
                            runCatching { api.issueVpnConfig(selectedNode?.region) }
                                .onSuccess { issued ->
                                    connected = true
                                    swipeProgress = 0f
                                    connectionStatus = "Config issued: ${issued.nodeName}"
                                }
                                .onFailure {
                                    connected = false
                                    swipeProgress = 0f
                                    connectionStatus = "Connect failed: ${it.message.orEmpty()}"
                                }
                            runCatching { api.bootstrap() }.onSuccess { bootstrap ->
                                nodes = bootstrap.nodes
                                selectedNode = bootstrap.nodes.firstOrNull()
                                quota = bootstrap.quota
                                serverStatus = bootstrap.nodes.firstOrNull()?.let { "${it.region} | ${it.health}" }
                                    ?: "No VPN nodes configured"
                            }
                            busy = false
                        }
                    }
                },
                onDisconnect = {
                    if (!busy) {
                        scope.launch {
                            busy = true
                            connectionStatus = "Revoking VPN config..."
                            runCatching { api.revokeActiveConfig() }
                                .onSuccess {
                                    connected = false
                                    swipeProgress = 0f
                                    connectionStatus = "Disconnected"
                                }
                                .onFailure {
                                    connectionStatus = "Disconnect failed: ${it.message.orEmpty()}"
                                }
                            busy = false
                        }
                    }
                },
                onProgressChange = { swipeProgress = it }
            )

            Spacer(modifier = Modifier.height(26.dp))

            Text(
                text = if (connected) "Connected" else "Disconnected",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 44.sp,
                lineHeight = 44.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Text(
                text = if (connected) "Your connection is encrypted and secure" else "Your traffic is exposed",
                color = TextSecondary,
                fontSize = 17.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.weight(1f))
            ServerCard(node = selectedNode, serverStatus = serverStatus)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = connectionStatus ?: if (connected) "You are protected" else "Swipe up and hold to activate protection",
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun HeaderRow(connected: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (connected) Color(0xFF0B4C35) else AccentWarning)
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Text(
            text = if (connected) "Protected" else "Unprotected",
            color = if (connected) AccentGreen else Color(0xFF8C98B6),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(listOf(AccentPurple, AccentBlue))
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Security, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
        }

        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text("SecureVPN", color = TextPrimary, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, lineHeight = 40.sp)
            Text("Privacy First", color = TextSecondary, fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.weight(1f))

    }
}

@Composable
private fun TrafficCard(quota: QuotaStatus?) {
    val totalGb = quota?.totalGb ?: 10.0
    val usedGb = quota?.usedGb ?: 0.0
    val remainingGb = quota?.remainingGb ?: totalGb
    val progress = quota?.usedPercent ?: 0f
    val usedText = String.format(Locale.US, "%.2f GB out of %.2f GB", usedGb, totalGb)
    val remainingText = String.format(Locale.US, "Left: %.2f GB", remainingGb)
    val percentText = "${(progress * 100).roundToInt()}%"

    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurface.copy(alpha = 0.92f)),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Security, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Traffic limit", color = TextPrimary, fontSize = 16.sp)
                Spacer(modifier = Modifier.weight(1f))
                Text(usedText, color = TextSecondary, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF23314F))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceAtLeast(0.02f))
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(listOf(AccentPurple, AccentBlue)))
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(remainingText, color = TextSecondary, fontSize = 14.sp)
                Spacer(modifier = Modifier.weight(1f))
                Text(percentText, color = AccentGreen, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun StatsRow() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        StatCard("Download", "10.3 K/s", AccentGreen, Icons.Rounded.ArrowDownward, Modifier.weight(1f))
        StatCard("Upload", "10.3 K/s", AccentBlue, Icons.Rounded.ArrowUpward, Modifier.weight(1f))
        StatCard("Duration", "00:35:07", AccentPurple, Icons.Rounded.Schedule, Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(title: String, value: String, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CardSurface.copy(alpha = 0.9f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(title, color = color, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LockControl(
    connected: Boolean,
    progress: Float,
    lockColor: Color,
    enabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onProgressChange: (Float) -> Unit
) {
    val threshold = 220f
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var connectTriggered by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(220.dp)
                .clip(CircleShape)
                .background(Color(0x33FFFFFF))
        )
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(if (connected) AccentGreen.copy(alpha = 0.14f) else Color(0x220D1020))
                .drawBehind {
                    drawCircle(color = lockColor.copy(alpha = if (connected) 0.42f else 0.12f), radius = size.minDimension * (0.58f + (progress * 0.35f)))
                }
                .then(
                    if (!connected) {
                        Modifier
                            .clickable(enabled = enabled) {
                                if (!connectTriggered) {
                                    connectTriggered = true
                                    onConnect()
                                }
                            }
                            .pointerInput(enabled) {
                                if (!enabled) return@pointerInput
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        connectTriggered = false
                                        dragProgress = progress
                                    },
                                    onVerticalDrag = { change, dragAmount ->
                                        change.consume()
                                        if (dragProgress >= 1f && dragAmount < 0f) return@detectVerticalDragGestures

                                        val delta = (-dragAmount / threshold).coerceIn(-1f, 1f)
                                        dragProgress = (dragProgress + delta).coerceIn(0f, 1f)
                                        onProgressChange(dragProgress)

                                        if (dragProgress >= 1f) {
                                            if (connectTriggered) return@detectVerticalDragGestures
                                            connectTriggered = true
                                            onConnect()
                                        }
                                    },
                                    onDragEnd = {
                                        if (dragProgress >= 0.92f && !connectTriggered) {
                                            connectTriggered = true
                                            onConnect()
                                        } else {
                                            dragProgress = 0f
                                            onProgressChange(0f)
                                        }
                                    },
                                    onDragCancel = {
                                        dragProgress = 0f
                                        onProgressChange(0f)
                                    }
                                )
                            }
                    } else {
                        Modifier.clickable(enabled = enabled) { onDisconnect() }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (connected) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                contentDescription = null,
                tint = lockColor,
                modifier = Modifier.size(56.dp)
            )
        }

        if (!connected) {
            val pct = (progress * 100).roundToInt()
            if (pct > 0) {
                Text(
                    text = "$pct%",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun ServerCard(node: VpnNode?, serverStatus: String) {
    val country = node?.countryCode?.takeIf { it.isNotBlank() } ?: "RU"
    val title = node?.name?.takeIf { it.isNotBlank() } ?: "VPN node"
    val subtitle = node?.let { "${it.region} | ${it.health} | load ${it.load}%" } ?: serverStatus

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = GlassSurface.copy(alpha = 0.95f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = country.uppercase(Locale.US), fontSize = 22.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(title, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 18.sp)
                Text(subtitle.ifBlank { "No VPN nodes configured" }, color = TextSecondary, fontSize = 16.sp)
            }
            Spacer(modifier = Modifier.weight(1f))
            Text("• API", color = AccentGreen, fontSize = 18.sp)
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(26.dp))
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun VpnPreview() {
    SecureVpnTheme {
        ServerCard(node = null, serverStatus = "Saint-Petersburg")
    }
}
