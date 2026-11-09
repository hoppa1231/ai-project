package app.chspch.client

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

enum class ServiceReachability { IDLE, AVAILABLE, UNAVAILABLE }
internal data class ServiceProbe(val state: ServiceReachability = ServiceReachability.IDLE, val httpCode: Int? = null)

/** Representative checks follow the current routing and run only while this screen is resumed. */
@Composable
fun ConnectivityPanel(linkState: LinkState, night: Boolean) {
    var results by remember { mutableStateOf(List(3) { ServiceProbe() }) }
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalView.current.findViewTreeLifecycleOwner()?.lifecycle
    val labels = listOf("Российские сервисы", "Google", "Телеграм")
    val endpoints = listOf("https://ya.ru", "https://www.google.com/generate_204", "https://telegram.org")
    val icons = listOf(R.drawable.ic_service_russian, R.drawable.ic_service_foreign, R.drawable.ic_service_blocked)
    val color = if (night) Gold else Burgundy
    LaunchedEffect(linkState, refresh, lifecycle) {
        results = List(3) { ServiceProbe() }
        if (linkState == LinkState.Connecting || linkState == LinkState.Paused) return@LaunchedEffect
        lifecycle?.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (linkState == LinkState.On) delay(1500)
            while (isActive) {
                val startedAt = SystemClock.elapsedRealtime()
                coroutineScope {
                    endpoints.mapIndexed { index, endpoint -> launch {
                        val result = withContext(Dispatchers.IO) { probeService(endpoint) }
                        results = results.toMutableList().also { it[index] = result }
                    } }.joinAll()
                }
                delay((10_000L - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0L))
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        labels.forEachIndexed { index, label ->
            val result = results[index]
            val status = when (result.state) {
                ServiceReachability.IDLE -> if (result.httpCode != null) "сервер отвечает, проверка ограничена: ${result.httpCode}" else "не проверено"
                ServiceReachability.AVAILABLE -> "работает"
                ServiceReachability.UNAVAILABLE -> "нет ответа"
            }
            Row(Modifier.weight(1f).border(1.dp, color.copy(alpha = .7f))
                .background(if (night) Color(0x44200808) else Color.White.copy(alpha = .22f))
                .clickable { refresh++ }
                .semantics { contentDescription = "$label: $status. Проверка ${endpoints[index]}. Нажмите для обновления" }
                .padding(horizontal = 9.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly) {
                // Original PNG alpha masks are preserved; only their display tint changes.
                Image(painterResource(icons[index]), contentDescription = null,
                    colorFilter = ColorFilter.tint(color), modifier = Modifier.size(34.dp))
                GlassStatusLamp(result.state, color)
            }
        }
    }
}

@Composable
private fun GlassStatusLamp(state: ServiceReachability, metal: Color) {
    Canvas(Modifier.size(29.dp)) {
        val r = size.minDimension * .29f
        val glow = when (state) {
            ServiceReachability.AVAILABLE -> Color(0xFF8AD475)
            ServiceReachability.UNAVAILABLE -> Color(0xFFE76E49)
            ServiceReachability.IDLE -> Color(0xFF464739)
        }
        if (state != ServiceReachability.IDLE) {
            drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = .4f), glow.copy(alpha = .1f), Color.Transparent), center, r * 1.65f), r * 1.65f)
        }
        // Recessed metal bezel, convex glass, lit filament and a reflected highlight.
        drawCircle(Color.Black.copy(alpha = .55f), r * 1.13f, center + Offset(0f, 1.dp.toPx()))
        drawCircle(Brush.linearGradient(listOf(metal.copy(alpha = .95f), Color(0xFF352513), metal.copy(alpha = .65f))), r * 1.12f)
        drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = if (state == ServiceReachability.IDLE) .5f else 1f), glow.copy(alpha = .72f), Color(0xFF192018)), center - Offset(r * .18f, r * .2f), r * 1.35f), r)
        if (state != ServiceReachability.IDLE) drawCircle(glow.copy(alpha = .85f), r * .27f, center + Offset(0f, r * .12f))
        drawCircle(Color.Black.copy(alpha = .5f), r, style = Stroke(.7.dp.toPx()))
        drawOval(Brush.verticalGradient(listOf(Color.White.copy(alpha = .7f), Color.White.copy(alpha = .04f))),
            topLeft = center - Offset(r * .62f, r * .75f), size = Size(r * 1.1f, r * .62f))
        drawArc(Color.White.copy(alpha = .23f), 30f, 100f, false,
            topLeft = center - Offset(r * .79f, r * .79f), size = Size(r * 1.58f, r * 1.58f), style = Stroke(.6.dp.toPx()))
    }
}

internal fun probeService(endpoint: String): ServiceProbe {
    var connection: HttpURLConnection? = null
    return try {
        connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.connectTimeout = 4000
        connection.readTimeout = 4000
        connection.requestMethod = "HEAD"
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        val code = connection.responseCode
        ServiceProbe(when (code) {
            in 200..399 -> ServiceReachability.AVAILABLE
            // Anti-bot challenges and throttling prove a response, not whether the site is usable.
            403, 429 -> ServiceReachability.IDLE
            else -> ServiceReachability.UNAVAILABLE
        }, code)
    } catch (_: Exception) { ServiceProbe(ServiceReachability.UNAVAILABLE) } finally { connection?.disconnect() }
}
