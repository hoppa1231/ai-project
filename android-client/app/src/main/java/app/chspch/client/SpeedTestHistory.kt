package app.chspch.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class SavedSpeedTest(
    val id: String = UUID.randomUUID().toString(),
    val measuredAt: Long,
    val averageMbps: Float,
    val peakMbps: Float,
    val pingMs: Int?,
    val downloadedBytes: Long
) {
    fun uiState() = SpeedTestUiState(currentMbps = averageMbps, averageMbps = averageMbps,
        peakMbps = peakMbps, pingMs = pingMs, downloadedBytes = downloadedBytes, status = "последний сохранённый замер")

    fun isValid() = id.isNotBlank() && measuredAt > 0 && averageMbps.isFinite() && averageMbps >= 0 &&
        peakMbps.isFinite() && peakMbps >= 0 && downloadedBytes > 0 && (pingMs == null || pingMs >= 0)
}

internal object SpeedHistoryCodec {
    const val MAX_RESULTS = 2

    fun encode(results: List<SavedSpeedTest>): String = JSONArray().apply {
        results.filter { it.isValid() }.distinctBy { it.id }.take(MAX_RESULTS).forEach { result ->
            put(JSONObject().put("id", result.id).put("measuredAt", result.measuredAt)
                .put("averageMbps", result.averageMbps).put("peakMbps", result.peakMbps)
                .put("pingMs", result.pingMs ?: JSONObject.NULL).put("downloadedBytes", result.downloadedBytes))
        }
    }.toString()

    fun decode(raw: String?): List<SavedSpeedTest> = runCatching {
        val array = JSONArray(raw ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            runCatching {
                val value = array.getJSONObject(index)
                SavedSpeedTest(value.getString("id"), value.getLong("measuredAt"),
                    value.getDouble("averageMbps").toFloat(), value.getDouble("peakMbps").toFloat(),
                    if (value.isNull("pingMs")) null else value.getInt("pingMs"), value.getLong("downloadedBytes"))
                    .takeIf { it.isValid() }
            }.getOrNull()
        }.distinctBy { it.id }.take(MAX_RESULTS)
    }.getOrDefault(emptyList())
}

internal class SpeedTestHistory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("speed_test_history", Context.MODE_PRIVATE)
    fun load(): List<SavedSpeedTest> = SpeedHistoryCodec.decode(prefs.getString("completed_results", null))
    fun save(results: List<SavedSpeedTest>) {
        prefs.edit().putString("completed_results", SpeedHistoryCodec.encode(results)).apply()
    }
}
