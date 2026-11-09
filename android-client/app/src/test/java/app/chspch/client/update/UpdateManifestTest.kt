package app.chspch.client.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateManifestTest {
    private val endpoint = "https://tech-supp-test.ru/updates/android/latest.json"
    private fun manifest() = JSONObject().put("schemaVersion", 1).put("packageName", "app.chspch.client")
        .put("versionCode", 4).put("versionName", "1.2.1").put("notes", "Обновление")
        .put("artifacts", JSONObject().put("arm64-v8a", JSONObject()
            .put("url", "https://tech-supp-test.ru/updates/android/releases/app4.apk")
            .put("sha256", "a".repeat(64)).put("sizeBytes", 1024)))

    @Test fun selectsSupportedAbiAndReadsVersion() {
        val update = UpdateManifest.parse(manifest().toString(), endpoint, listOf("x86_64", "arm64-v8a"))!!
        assertEquals(4, update.versionCode); assertEquals("arm64-v8a", update.abi)
        assertNull(UpdateManifest.parse(manifest().toString(), endpoint, listOf("x86")))
    }
    @Test fun rejectsUntrustedDownloadLocations() {
        for (url in listOf("http://tech-supp-test.ru/updates/android/a.apk", "https://evil.test/updates/android/a.apk",
            "https://tech-supp-test.ru:8443/updates/android/a.apk", "https://user@tech-supp-test.ru/updates/android/a.apk",
            "https://tech-supp-test.ru/elsewhere/a.apk")) {
            val raw = manifest(); raw.getJSONObject("artifacts").getJSONObject("arm64-v8a").put("url", url)
            assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(raw.toString(), endpoint, listOf("arm64-v8a")) }
        }
    }
    @Test fun rejectsWrongPackageHashAndOversizedFiles() {
        val wrong = manifest().put("packageName", "another.app")
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(wrong.toString(), endpoint, listOf("arm64-v8a")) }
        for ((key, value) in listOf("sha256" to "broken", "sizeBytes" to 0, "sizeBytes" to UpdateManifest.MAX_APK_BYTES + 1)) {
            val raw = manifest(); raw.getJSONObject("artifacts").getJSONObject("arm64-v8a").put(key, value)
            assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(raw.toString(), endpoint, listOf("arm64-v8a")) }
        }
    }
}
