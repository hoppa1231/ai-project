package app.chspch.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpeedHistoryCodecTest {
    @Test fun completedMeasurementsSurviveRoundTripWithUnknownPing() {
        val results = listOf(
            SavedSpeedTest("second", 2000L, 52.35f, 74.6f, null, 2_345_678L),
            SavedSpeedTest("first", 1000L, 12.5f, 15f, 23, 1_234_567L)
        )
        assertEquals(results, SpeedHistoryCodec.decode(SpeedHistoryCodec.encode(results)))
        assertEquals(52.35f, SpeedHistoryCodec.decode(SpeedHistoryCodec.encode(results))[0].uiState().averageMbps, 0f)
    }

    @Test fun damagedEntryDoesNotDiscardOtherMeasurements() {
        val valid = SavedSpeedTest("valid", 1000L, 12f, 15f, 20, 1024L)
        val array = JSONArray(SpeedHistoryCodec.encode(listOf(valid))).put(JSONObject().put("id", "broken"))
        assertEquals(listOf(valid), SpeedHistoryCodec.decode(array.toString()))
        assertTrue(SpeedHistoryCodec.decode("invalid json").isEmpty())
    }

    @Test fun emptyAndNonFiniteResultsAreNotStored() {
        val result = SavedSpeedTest("valid", 1000L, 12f, 15f, 20, 1024L)
        val encoded = SpeedHistoryCodec.encode(listOf(result.copy(id = "empty", downloadedBytes = 0),
            result.copy(id = "invalid", averageMbps = Float.NaN), result))
        assertEquals(listOf(result), SpeedHistoryCodec.decode(encoded))
    }
}
