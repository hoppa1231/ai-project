package com.securevpn.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

fun Modifier.drawLampGlow(state: LinkState): Modifier = drawBehind {
    val glowColor = when (state) {
        LinkState.On -> GoldLight
        LinkState.Paused -> GoldDeep
        LinkState.Connecting -> OrangeSignal
        LinkState.Off -> Color.Transparent
    }
    if (glowColor != Color.Transparent) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(glowColor.copy(alpha = 0.65f), glowColor.copy(alpha = 0.18f), Color.Transparent),
                radius = 26.dp.toPx()
            ),
            radius = 26.dp.toPx(),
            center = center
        )
    }
}

@Composable
fun PosterFrame(background: Color, dark: Boolean, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF202126))
            .systemBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(background)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = if (dark) {
                            listOf(Gold.copy(alpha = 0.14f), Color.Transparent)
                        } else {
                            listOf(Color.Black.copy(alpha = 0.08f), Color.Transparent)
                        },
                        center = Offset(size.width * 0.35f, size.height * 0.2f),
                        radius = size.minDimension * 0.7f
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Black.copy(alpha = if (dark) 0.34f else 0.08f), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height),
                        radius = size.minDimension * 0.82f
                    )
                )
                val dotColor = if (dark) Gold.copy(alpha = 0.035f) else Ink.copy(alpha = 0.04f)
                val grainColor = if (dark) Bone.copy(alpha = 0.045f) else Ink.copy(alpha = 0.07f)
                for (x in 0..size.width.toInt() step 11) {
                    for (y in 0..size.height.toInt() step 11) {
                        val seed = (x * 31 + y * 17) % 9
                        drawCircle(
                            color = grainColor.copy(alpha = 0.025f + seed * 0.006f),
                            radius = (0.35f + seed * 0.08f).dp.toPx(),
                            center = Offset(x.toFloat() + seed, y.toFloat() - seed * 0.4f)
                        )
                    }
                }
                for (x in 0..size.width.toInt() step 24) {
                    for (y in 0..size.height.toInt() step 24) {
                        drawCircle(dotColor, radius = 0.9f, center = Offset(x.toFloat(), y.toFloat()))
                    }
                }
            }
            content()
        }
    }
}

@Composable
fun PosterTitle(text: String, color: Color, size: Int, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        fontFamily = Playfair,
        fontSize = size.sp,
        lineHeight = (size * 0.92f).sp,
        fontWeight = FontWeight.Black,
        fontStyle = FontStyle.Italic,
        textAlign = TextAlign.Center,
        modifier = modifier,
        style = TextStyle(
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.65f),
                offset = Offset(1f, 1f),
                blurRadius = 4f
            )
        )
    )
}

@Composable
fun OrnamentLine(color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(color.copy(alpha = 0.55f)))
        Text(" ◆ - ◇ - ★ - ◇ - ◆ ", color = color, fontSize = 12.sp)
        Box(Modifier.weight(1f).height(1.dp).background(color.copy(alpha = 0.55f)))
    }
}

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String,
    color: Color,
    subtitleColor: Color,
    titleSize: Int,
    overline: String = "★ ★ ★"
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        OrnamentLine(color = color)
        Spacer(Modifier.height(4.dp))
        Text(overline, color = color, fontFamily = PtSans, fontSize = 9.sp, letterSpacing = 4.sp, textAlign = TextAlign.Center)
        PosterTitle(title, color = color, size = titleSize, modifier = Modifier.fillMaxWidth())
        Text(
            subtitle,
            color = subtitleColor,
            fontFamily = Playfair,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Thin,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun VpnKnifeSwitch(
    state: LinkState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val switchInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .pointerInput(state) {
                detectVerticalDragGestures(
                    onDragStart = { dragDistance = 0f },
                    onVerticalDrag = { _, dragAmount -> dragDistance += dragAmount },
                    onDragEnd = {
                        if (state == LinkState.Off && dragDistance < -36f) onToggle()
                        if ((state == LinkState.On || state == LinkState.Paused) && dragDistance > 36f) onToggle()
                        dragDistance = 0f
                    },
                    onDragCancel = { dragDistance = 0f }
                )
            }
    ) {
        Image(
            painter = painterResource(
                if (state == LinkState.Off) R.drawable.vpn_power_widget_off else R.drawable.vpn_power_widget
            ),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(116.dp)
                .clickable(
                    interactionSource = switchInteraction,
                    indication = null
                ) { onToggle() }
        )
    }
}

@Composable
fun StatusPanel(linkState: LinkState, gold: Color, bone: Color, nightTheme: Boolean) {
    val statusText = when (linkState) {
        LinkState.On -> "НА СВЯЗИ"
        LinkState.Paused -> "НА ПАУЗЕ"
        LinkState.Connecting -> "СОЕДИНЯЕМЪ..."
        LinkState.Off -> "ОБЕСТОЧЕНО"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, gold)
            .padding(3.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, gold)
                .background(if (nightTheme) Color(0x99200808) else Color.White.copy(alpha = 0.38f))
                .padding(horizontal = 17.dp, vertical = 11.dp)
        ) {
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    "СОСТОЯНИЕ",
                    color = gold,
                    fontSize = 9.sp,
                    lineHeight = 8.sp,
                    letterSpacing = 3.sp,
                    fontFamily = PtSans,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .zIndex(-1f)
                )
                Text(
                    statusText,
                    color = when (linkState) {
                        LinkState.On -> Gold
                        LinkState.Paused -> GoldDeep
                        LinkState.Connecting -> OrangeSignal
                        LinkState.Off -> bone
                    },
                    fontSize = 16.sp,
                    lineHeight = 16.sp,
                    fontFamily = Russo,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(12.dp)
                    .background(
                        color = when (linkState) {
                            LinkState.On -> GoldLight
                            LinkState.Paused -> GoldDeep
                            LinkState.Connecting -> OrangeSignal
                            LinkState.Off -> Color(0xFF3A1A1A)
                        },
                        shape = CircleShape
                    )
                    .border(1.dp, gold.copy(alpha = 0.75f), CircleShape)
                    .drawLampGlow(linkState)
            )
            if (linkState == LinkState.On) {
                Stamp(
                    "УТВЕРЖДЕНО",
                    color = Gold,
                    animated = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .zIndex(100f)
                        .graphicsLayer {
                            translationY = -22f
                            translationX = 4f
                        }
                )
            }
        }
    }
}

@Composable
fun ServerTicket(server: ServerNode, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(if (enabled) Burgundy else Color(0x663A1010))
            .border(1.dp, if (enabled) Gold else Gold.copy(alpha = 0.45f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StarBox(active = enabled)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text("УЗЕЛ СВЯЗИ", color = Gold, fontSize = 8.sp, lineHeight = 8.sp, letterSpacing = 2.sp, fontFamily = PtSans, fontWeight = FontWeight.Normal)
            Text("${server.city} · ${server.node}", color = Bone, fontFamily = Russo, fontSize = 13.sp, lineHeight = 13.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = Gold, fontSize = 24.sp)
    }
}

@Composable
fun CurrentServerCard(server: ServerNode, nightTheme: Boolean) {
    val panel = if (nightTheme) Burgundy else Color(0xFFE9DDC0)
    val border = if (nightTheme) Gold else Burgundy
    val primary = if (nightTheme) Gold else Burgundy
    val text = if (nightTheme) Bone else Ink
    Box(
        modifier = Modifier
            .padding(12.dp)
            .fillMaxWidth()
            .border(0.5.dp, border)
            .padding(3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(panel)
                .border(1.dp, border)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StarBox(active = true, large = true, border = primary)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text("ТЕКУЩЕЕ ПОДКЛЮЧЕНІЕ", color = primary.copy(alpha = 0.62f), fontFamily = PtSans, fontSize = 8.sp, lineHeight = 8.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
                Text("${server.city} · ${server.node}", color = primary, fontFamily = Russo, fontSize = 15.sp, lineHeight = 15.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("откликъ ${server.ping} мс · загрузка ${server.load}%", color = text, fontFamily = Playfair, fontStyle = FontStyle.Italic, fontSize = 10.sp, lineHeight = 10.sp)
            }
        }
    }
}

@Composable
fun SearchBox(nightTheme: Boolean, telegramMode: Boolean = false) {
    val border = if (nightTheme) GoldDeep else Burgundy
    val text = if (nightTheme) Bone else InkSoft
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 0.dp)
            .fillMaxWidth()
            .height(42.dp)
            .background(if (nightTheme) Color(0x4D000000) else Color(0x22FFFFFF))
            .border(1.dp, border)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = border, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (telegramMode) "Поискъ конфига..." else "Поискъ узла связи...",
            color = text.copy(alpha = 0.62f),
            fontFamily = Playfair,
            fontStyle = FontStyle.Italic,
            fontSize = 11.sp
        )
    }
}

@Composable
fun ServerRow(server: ServerNode, selected: Boolean, stamping: Boolean, nightTheme: Boolean, onClick: () -> Unit) {
    val rowText = if (nightTheme) BoneLight else Ink
    val subText = if (nightTheme) Bone.copy(alpha = 0.7f) else InkSoft
    val selectedColor = if (nightTheme) Gold else Burgundy
    Box(modifier = Modifier.zIndex(if (selected || stamping) 20f else 0f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .background(if (selected) selectedColor.copy(alpha = 0.1f) else Color.Transparent)
                .clickable { onClick() }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StarBox(active = selected, border = selectedColor)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(server.city, color = if (selected) selectedColor else rowText, fontFamily = Russo, fontSize = 13.sp, lineHeight = 13.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(server.node, color = subText, fontFamily = Playfair, fontStyle = FontStyle.Italic, fontSize = 10.sp, lineHeight = 10.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${server.ping}мс", color = selectedColor, fontFamily = PtSans, fontSize = 10.sp, lineHeight = 10.sp)
                Box(Modifier.width(40.dp).height(3.dp).background(Color(0x80000000))) {
                    Box(
                        Modifier
                            .fillMaxWidth(server.load / 100f)
                            .height(3.dp)
                            .background(if (server.load < 30) Color(0xFF7ED070) else if (server.load < 60) Gold else OrangeSignal)
                    )
                }
            }
        }
        if (stamping) {
            Stamp(
                "УТВЕРЖДЕНО",
                color = if (nightTheme) Gold else Burgundy,
                stampSize = 8,
                animated = true,
                rotateDegrees = -7f,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .zIndex(80f)
                    .padding(end = 8.dp, top = 5.dp)
            )
        }
        if (selected) {
            Stamp(
                "УТВЕРЖДЕНО",
                color = if (nightTheme) Gold else Burgundy,
                stampSize = 8,
                animated = false,
                rotateDegrees = -7f,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .zIndex(80f)
                    .padding(end = 8.dp, top = 5.dp)
            )
            RoundStamp(
                "ВЫБРАНО",
                color = if (nightTheme) Gold else Burgundy,
                animated = true,
                sizeDp = 62,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .zIndex(90f)
                    .padding(end = 42.dp)
            )
        }
    }
}

@Composable
fun UserPermitCard(
    primary: Color,
    text: Color,
    border: Color,
    panel: Color,
    telegramAccount: com.securevpn.app.data.TelegramAccount?,
    telegramStatus: String?,
    onTelegramLogin: () -> Unit
) {
    val linked = telegramAccount != null
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .border(2.dp, border)
            .background(panel)
            .clickable { onTelegramLogin() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Canvas(Modifier.matchParentSize()) {
            for (i in 0 until 22) {
                val x = ((i * 29) % 101) / 101f * size.width
                val y = ((i * 43) % 89) / 89f * size.height
                drawCircle(border.copy(alpha = 0.08f), radius = (0.5f + i % 3).dp.toPx(), center = Offset(x, y))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.CenterStart)) {
            RoundSeal(color = primary, modifier = Modifier.size(54.dp))
            Spacer(Modifier.width(18.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text("ПОДПИСЧИКЪ №", color = text.copy(alpha = 0.62f), fontFamily = PtSans, fontSize = 9.sp, lineHeight = 9.sp, letterSpacing = 3.sp)
                Text(if (linked) "TELEGRAM" else "НЕ ПРИВЯЗАН", color = primary, fontFamily = Russo, fontSize = 17.sp, lineHeight = 17.sp)
                Text(
                    telegramStatus ?: "нажмите для входа через @${BuildConfig.TELEGRAM_BOT_USERNAME}",
                    color = text,
                    fontFamily = Playfair,
                    fontStyle = FontStyle.Italic,
                    fontSize = 11.sp,
                    lineHeight = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Stamp(if (linked) "ВХОДЪ" else "ВОЙТИ", color = primary, stampSize = 9, modifier = Modifier.align(Alignment.TopEnd).zIndex(80f))
    }
}

@Composable
fun RoundSeal(color: Color, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f - 3.dp.toPx()
            drawCircle(color.copy(alpha = 0.09f), radius = r, center = center)
            drawCircle(color, radius = r, center = center, style = Stroke(width = 1.4.dp.toPx()))
            drawCircle(color, radius = r * 0.68f, center = center, style = Stroke(width = 1.dp.toPx()))
            repeat(18) { i ->
                val a = i * (Math.PI * 2 / 18)
                val p = Offset(center.x + cos(a).toFloat() * r * 0.84f, center.y + sin(a).toFloat() * r * 0.84f)
                drawCircle(color.copy(alpha = 0.65f), radius = 0.8.dp.toPx(), center = p)
            }
        }
        Text("И·С", color = color, fontFamily = Russo, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun SettingsSection(
    title: String,
    primary: Color,
    border: Color,
    panel: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("§", color = primary, fontSize = 16.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(8.dp))
            Text(title, color = primary, fontFamily = Playfair, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Black, fontSize = 15.sp)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f).height(1.dp).background(border))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, border)
                .background(panel),
            content = content
        )
    }
}

@Composable
fun SettingsRow(
    index: String,
    title: String,
    subtitle: String,
    checked: Boolean? = null,
    onToggle: (() -> Unit)? = null,
    text: Color = Ink,
    soft: Color = InkSoft,
    night: Boolean = false
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clickable(enabled = onToggle != null) { onToggle?.invoke() }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(index, color = soft, fontFamily = PtSans, fontSize = 9.sp, modifier = Modifier.width(28.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(title, color = text, fontFamily = PtSans, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = soft, fontFamily = Playfair, fontStyle = FontStyle.Italic, fontSize = 10.sp, lineHeight = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (checked == null) {
                Text("›", color = text, fontFamily = PtSans, fontSize = 18.sp)
            } else {
                PaperToggle(on = checked, night = night)
            }
        }
        Canvas(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
        ) {
            drawLine(
                color = soft.copy(alpha = 0.65f),
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))
            )
        }
    }
}

@Composable
fun PaperToggle(on: Boolean, night: Boolean = false) {
    val border = if (night) GoldDeep else Ink
    Box(
        modifier = Modifier
            .width(61.dp)
            .height(31.dp)
    ) {
        Box(
            modifier = Modifier
                .width(54.dp)
                .height(26.dp)
                .graphicsLayer {
                    translationX = 2.dp.toPx()
                    translationY = 2.dp.toPx()
                }
                .background(Ink)
        )
        Box(
            modifier = Modifier
                .width(54.dp)
                .height(26.dp)
                .background(if (on) Burgundy else BoneLight)
                .border(1.5.dp, border)
                .drawBehind {
                    drawRect(Color.Black.copy(alpha = 0.2f), size = Size(size.width, 0.dp.toPx()))
                }
                .padding(2.dp)
        ) {
            Text(
                if (on) "I" else "O",
                color = if (on) GoldLight else border,
                fontFamily = Russo,
                fontSize = 10.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(if (on) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 7.dp)
            )
            Box(
                modifier = Modifier
                    .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                    .size(23.dp)
                    .background(if (on) GoldLight else Color(0xFFF8F1DD))
                    .border(1.dp, border)
                    .drawBehind {
                        drawRect(Color.Black.copy(alpha = 0.16f), size = Size(size.width, 3.dp.toPx()))
                    }
            )
        }
    }
}

@Composable
fun SharpStar(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val outer = size.minDimension * 0.48f
        val inner = outer * 0.36f
        val c = center
        val path = Path()
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = -Math.PI / 2.0 + i * Math.PI / 5.0
            val p = Offset(
                c.x + cos(angle).toFloat() * radius,
                c.y + sin(angle).toFloat() * radius
            )
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
        drawPath(path, color)
    }
}

@Composable
fun SpeedGauge(progress: Float, modifier: Modifier = Modifier) {
    val boundedProgress = progress.coerceIn(0f, 1f)
    val arrowRotation = -SPEED_GAUGE_SWEEP_ANGLE / 2f + boundedProgress * SPEED_GAUGE_SWEEP_ANGLE
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val gaugeSide = min(maxWidth.value, maxHeight.value).dp
        val arrowSize = (gaugeSide.value * SPEED_GAUGE_ARROW_SIZE_RATIO).dp
        val arrowPivotOffset = with(LocalDensity.current) {
            (arrowSize.value * (SPEED_GAUGE_ARROW_PIVOT_Y - 0.5f)).dp.toPx()
        }
        Image(
            painter = painterResource(R.drawable.speedomentr),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        Image(
            painter = painterResource(R.drawable.arrow),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(arrowSize)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, SPEED_GAUGE_ARROW_PIVOT_Y)
                    rotationZ = arrowRotation
                    translationY = -arrowPivotOffset
                }
        )
    }
}

@Composable
fun FlipNumber(value: Float, modifier: Modifier = Modifier, subtext: Color) {
    val text = "%.1f".format(java.util.Locale.US, value).padStart(5, '0')
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom
    ) {
        text.forEach { ch ->
            if (ch == '.') {
                Text(".", color = subtext, fontFamily = PtSans, fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 3.dp))
            } else {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 1.dp)
                        .width(29.dp)
                        .height(41.dp)
                        .background(Brush.verticalGradient(listOf(Color(0xFFF5EBD0), Color(0xFFD4C298), Color(0xFFE8D9B0))))
                        .border(1.dp, Color(0xFF6A4A10)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF3A2410)).align(Alignment.Center))
                    Text(ch.toString(), color = Color(0xFF1A0C04), fontFamily = PtSans, fontWeight = FontWeight.Black, fontSize = 28.sp)
                }
            }
        }
    }
}

@Composable
fun MetricBox(label: String, value: String, nightTheme: Boolean, modifier: Modifier = Modifier) {
    val primary = if (nightTheme) Gold else Burgundy
    val border = if (nightTheme) GoldDeep else Burgundy
    Column(
        modifier = modifier
            .background(if (nightTheme) Color.Black.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.38f))
            .border(1.dp, border)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = border, fontSize = 7.sp, lineHeight = 7.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        Text(value, color = primary, fontSize = 14.sp, lineHeight = 14.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun StarBox(active: Boolean, large: Boolean = false, border: Color = Gold) {
    Box(
        modifier = Modifier
            .size(if (large) 36.dp else 28.dp)
            .border(if (large) 2.dp else 1.dp, if (active) border else border.copy(alpha = 0.65f))
            .background(if (active) Burgundy else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        SharpStar(color = border, modifier = Modifier.size(if (large) 18.dp else 14.dp))
    }
}

@Composable
fun Stamp(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    stampSize: Int = 9,
    animated: Boolean = false,
    rotateDegrees: Float = -7f
) {
    var placed by remember(text) { mutableStateOf(!animated) }
    LaunchedEffect(text, animated) {
        if (animated) {
            placed = false
            delay(25)
            placed = true
        }
    }
    val scale by animateFloatAsState(if (placed) 1f else 2.35f, tween(560), label = "stamp-scale")
    val y by animateFloatAsState(if (placed) 0f else -42f, tween(560), label = "stamp-y")
    val alpha by animateFloatAsState(if (placed) 0.96f else 0f, tween(260), label = "stamp-alpha")

    Box(
        modifier = modifier
            .zIndex(100f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = y
                this.alpha = alpha
                rotationZ = rotateDegrees
            }
            .padding(2.dp)
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(
                color = Color(0x33200808),
                topLeft = Offset(0f, 0f),
                size = size
            )
            drawRect(
                color = BoneLight.copy(alpha = 0.95f),
                topLeft = Offset(0f, 0f),
                size = size,
                style = Stroke(width = 3.dp.toPx())
            )
            drawRect(
                color = color.copy(alpha = 0.94f),
                topLeft = Offset(3.dp.toPx(), 3.dp.toPx()),
                size = Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()),
                style = Stroke(width = 2.dp.toPx())
            )
            drawRect(
                color = color.copy(alpha = 0.28f),
                topLeft = Offset(6.dp.toPx(), 6.dp.toPx()),
                size = Size(size.width - 12.dp.toPx(), size.height - 12.dp.toPx()),
                style = Stroke(width = 1.dp.toPx())
            )
            for (i in 0 until 16) {
                val x = ((i * 23) % 97) / 97f * size.width
                val yy = ((i * 41) % 83) / 83f * size.height
                drawCircle(color.copy(alpha = 0.16f), radius = (0.45f + (i % 3) * 0.25f).dp.toPx(), center = Offset(x, yy))
            }
        }
        Text(
            text = text,
            color = color.copy(alpha = 0.94f),
            fontFamily = Russo,
            fontSize = stampSize.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun RoundStamp(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    animated: Boolean = false,
    sizeDp: Int = 68
) {
    var placed by remember(text, animated) { mutableStateOf(!animated) }
    LaunchedEffect(text, animated) {
        if (animated) {
            placed = false
            delay(35)
            placed = true
        }
    }
    val scale by animateFloatAsState(if (placed) 1f else 2.2f, tween(560), label = "round-stamp-scale")
    val y by animateFloatAsState(if (placed) 0f else -54f, tween(560), label = "round-stamp-y")
    val alpha by animateFloatAsState(if (placed) 0.95f else 0f, tween(260), label = "round-stamp-alpha")

    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .zIndex(110f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = y
                this.alpha = alpha
                rotationZ = -12f
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f - 4.dp.toPx()
            val center = this.center
            drawCircle(color.copy(alpha = 0.16f), radius = radius, center = center)
            drawCircle(color.copy(alpha = 0.82f), radius = radius, center = center, style = Stroke(width = 2.4.dp.toPx()))
            drawCircle(color.copy(alpha = 0.55f), radius = radius * 0.78f, center = center, style = Stroke(width = 1.3.dp.toPx()))
            for (i in 0 until 34) {
                val angle = (i * 37f) * Math.PI / 180f
                val r = radius * (0.18f + (i % 7) * 0.105f)
                drawCircle(
                    color.copy(alpha = 0.18f),
                    radius = (0.6f + (i % 4) * 0.22f).dp.toPx(),
                    center = Offset(center.x + cos(angle).toFloat() * r, center.y + sin(angle).toFloat() * r)
                )
            }
        }
        Text(
            text,
            color = color,
            fontSize = 11.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}
