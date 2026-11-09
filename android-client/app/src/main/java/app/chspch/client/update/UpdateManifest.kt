package app.chspch.client.update

import org.json.JSONObject
import java.net.URI

data class AppUpdate(val versionCode: Int, val versionName: String, val notes: String,
    val abi: String, val url: String, val sha256: String, val sizeBytes: Long)

object UpdateManifest {
    const val MAX_APK_BYTES = 250L * 1024 * 1024
    fun parse(raw: String, manifestUrl: String, supportedAbis: List<String>): AppUpdate? {
        val root = JSONObject(raw)
        require(root.getInt("schemaVersion") == 1) { "Неподдерживаемый формат обновления" }
        require(root.getString("packageName") == "app.chspch.client") { "Другой пакет приложения" }
        val code = root.getInt("versionCode")
        val name = root.getString("versionName")
        require(code > 0 && name.isNotBlank() && name.length <= 40)
        val artifacts = root.getJSONObject("artifacts")
        val abi = supportedAbis.firstOrNull { artifacts.has(it) } ?: return null
        val artifact = artifacts.getJSONObject(abi)
        val url = URI(artifact.getString("url"))
        val origin = URI(manifestUrl)
        require(url.scheme == "https" && url.host == origin.host && url.port == origin.port &&
            url.rawUserInfo == null && url.fragment == null && url.query == null &&
            url.path.startsWith("/updates/android/") && url.path.split('/').none { it == "." || it == ".." }) {
            "Недопустимый адрес обновления"
        }
        val sha = artifact.getString("sha256").lowercase()
        require(sha.matches(Regex("[a-f0-9]{64}"))) { "Некорректная контрольная сумма" }
        val size = artifact.getLong("sizeBytes")
        require(size in 1..MAX_APK_BYTES) { "Некорректный размер APK" }
        return AppUpdate(code, name, root.optString("notes").take(4000), abi, url.toString(), sha, size)
    }
}
