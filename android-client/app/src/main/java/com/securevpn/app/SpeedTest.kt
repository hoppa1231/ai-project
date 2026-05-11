package com.securevpn.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

data class SpeedTestUiState(
    val running: Boolean = false,
    val currentMbps: Float = 0f,
    val averageMbps: Float = 0f,
    val peakMbps: Float = 0f,
    val pingMs: Int? = null,
    val downloadedBytes: Long = 0L,
    val status: String = "готовъ къ поверке",
    val error: String? = null
)

data class SpeedTestResult(
    val averageMbps: Float,
    val peakMbps: Float,
    val downloadedBytes: Long
)

suspend fun measureInternetSpeed(
    onPing: suspend (Int) -> Unit,
    onSample: suspend (currentMbps: Float, averageMbps: Float, peakMbps: Float, downloadedBytes: Long) -> Unit
): SpeedTestResult {
    val pingMs = measureLatencyMs()
    withContext(Dispatchers.Main) { onPing(pingMs) }

    val testUrls = listOf(
        "https://speedtest.selectel.ru/100MB",
        "https://ru.edisglobal.com/100MB.test",
        "http://speedtest.rastrnet.ru/100MB.zip",
        "https://speed.cloudflare.com/__down?bytes=50000000",
        "https://proof.ovh.net/files/100Mb.dat",
        "https://ash-speed.hetzner.com/100MB.bin"
    )
    var lastError: Throwable? = null
    for (url in testUrls) {
        try {
            return downloadSpeed(url, onSample)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            lastError = error
        }
    }
    throw IllegalStateException("Тестовые серверы недоступны", lastError)
}

fun speedToGaugeProgress(speedMbps: Float): Float {
    val boundedSpeed = when {
        speedMbps.isNaN() -> 0f
        speedMbps < 0f -> 0f
        speedMbps > SPEED_GAUGE_MAX_MBPS -> SPEED_GAUGE_MAX_MBPS
        else -> speedMbps
    }
    return boundedSpeed / SPEED_GAUGE_MAX_MBPS
}

fun formatSpeed(value: Float): String =
    if (value >= 100f) value.roundToInt().toString() else "%.1f".format(java.util.Locale.US, value)

fun formatDataMb(value: Float): String =
    if (value >= 10f) value.roundToInt().toString() else "%.1f".format(java.util.Locale.US, value)

private suspend fun measureLatencyMs(): Int = withContext(Dispatchers.IO) {
    val attempts = listOf(
        "https://speedtest.selectel.ru/100MB",
        "https://ru.edisglobal.com/100MB.test",
        "http://speedtest.rastrnet.ru/100MB.zip",
        "https://speed.cloudflare.com/__down?bytes=1",
        "https://www.google.com/generate_204",
        "https://www.cloudflare.com/cdn-cgi/trace"
    )
    attempts.firstNotNullOfOrNull { url ->
        runCatching {
            val start = System.nanoTime()
            val connection = (URL(cacheBustedUrl(url)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4_000
                readTimeout = 4_000
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", "SecureVPN-Android/${BuildConfig.VERSION_NAME}")
            }
            try {
                connection.inputStream.use { stream ->
                    val buffer = ByteArray(16)
                    stream.read(buffer)
                }
                ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    } ?: 0
}

private suspend fun downloadSpeed(
    url: String,
    onSample: suspend (currentMbps: Float, averageMbps: Float, peakMbps: Float, downloadedBytes: Long) -> Unit
): SpeedTestResult = withContext(Dispatchers.IO) {
    val connection = (URL(cacheBustedUrl(url)).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8_000
        readTimeout = 8_000
        useCaches = false
        setRequestProperty("Cache-Control", "no-cache")
        setRequestProperty("User-Agent", "SecureVPN-Android/${BuildConfig.VERSION_NAME}")
    }

    try {
        val code = connection.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("Сервер замера ответил $code")
        }
        connection.inputStream.use { stream ->
            readSpeedStream(stream, onSample)
        }
    } finally {
        connection.disconnect()
    }
}

private suspend fun readSpeedStream(
    stream: InputStream,
    onSample: suspend (currentMbps: Float, averageMbps: Float, peakMbps: Float, downloadedBytes: Long) -> Unit
): SpeedTestResult {
    val buffer = ByteArray(64 * 1024)
    val startNs = System.nanoTime()
    var sampleStartNs = startNs
    var lastUiNs = startNs
    var totalBytes = 0L
    var sampleBytes = 0L
    var peakMbps = 0f
    var smoothedMbps = 0f

    while (totalBytes < SPEED_TEST_MAX_BYTES) {
        val read = stream.read(buffer)
        if (read <= 0) break
        totalBytes += read
        sampleBytes += read

        val now = System.nanoTime()
        if (now - startNs >= SPEED_TEST_MAX_NS || now - lastUiNs >= SPEED_TEST_SAMPLE_NS) {
            val sampleSeconds = ((now - sampleStartNs).coerceAtLeast(1L)) / 1_000_000_000.0
            val totalSeconds = ((now - startNs).coerceAtLeast(1L)) / 1_000_000_000.0
            val currentMbps = ((sampleBytes * 8.0) / sampleSeconds / 1_000_000.0).toFloat()
            val averageMbps = ((totalBytes * 8.0) / totalSeconds / 1_000_000.0).toFloat()
            smoothedMbps = if (smoothedMbps == 0f) currentMbps else smoothedMbps * 0.68f + currentMbps * 0.32f
            peakMbps = peakMbps.coerceAtLeast(smoothedMbps)
            withContext(Dispatchers.Main) {
                onSample(smoothedMbps, averageMbps, peakMbps, totalBytes)
            }
            sampleBytes = 0L
            sampleStartNs = now
            lastUiNs = now
        }

        if (now - startNs >= SPEED_TEST_MAX_NS) break
    }

    val totalSeconds = ((System.nanoTime() - startNs).coerceAtLeast(1L)) / 1_000_000_000.0
    val averageMbps = ((totalBytes * 8.0) / totalSeconds / 1_000_000.0).toFloat()
    return SpeedTestResult(
        averageMbps = averageMbps,
        peakMbps = peakMbps.coerceAtLeast(averageMbps),
        downloadedBytes = totalBytes
    )
}

private fun cacheBustedUrl(url: String): String {
    val separator = if (url.contains("?")) "&" else "?"
    return "$url${separator}cacheBust=${System.nanoTime()}"
}

private const val SPEED_TEST_MAX_BYTES = 32L * 1024L * 1024L
private const val SPEED_TEST_MAX_NS = 10_000_000_000L
private const val SPEED_TEST_SAMPLE_NS = 120_000_000L
private const val SPEED_GAUGE_MAX_MBPS = 100f

const val SPEED_GAUGE_SWEEP_ANGLE = 270f
const val SPEED_GAUGE_ARROW_SIZE_RATIO = 0.52f
const val SPEED_GAUGE_ARROW_PIVOT_Y = 0.824f
