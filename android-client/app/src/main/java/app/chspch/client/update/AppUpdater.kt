package app.chspch.client.update

import android.app.*
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import app.chspch.client.BuildConfig
import app.chspch.client.MainActivity
import app.chspch.client.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class AppUpdater(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val downloads = this.context.getSystemService(DownloadManager::class.java)
    private val manifestUrl = BuildConfig.API_BASE_URL.trimEnd('/') + "/updates/android/latest.json"

    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        val connection = URL(manifestUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        try {
            require(connection.responseCode == 200) { "Сервер обновлений недоступен" }
            val bytes = connection.inputStream.use { input ->
                val buffer = ByteArray(65_537)
                var count = 0
                while (count < buffer.size) {
                    val n = input.read(buffer, count, buffer.size - count)
                    if (n < 0) break
                    count += n
                }
                buffer.copyOf(count)
            }
            require(bytes.size <= 65_536) { "Слишком большой ответ сервера" }
            val raw = bytes.toString(Charsets.UTF_8)
            val update = UpdateManifest.parse(raw, manifestUrl, Build.SUPPORTED_ABIS.toList())
            if (update != null && update.versionCode > BuildConfig.VERSION_CODE) {
                prefs.edit().putString("available", raw).apply()
                update
            } else {
                prefs.edit().remove("available").apply()
                NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
                null
            }
        } finally { connection.disconnect() }
    }

    fun available(): AppUpdate? = parseSaved("available")?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    fun pending(): AppUpdate? = parseSaved("pending")?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    private fun parseSaved(key: String): AppUpdate? = prefs.getString(key, null)?.let {
        runCatching { UpdateManifest.parse(it, manifestUrl, Build.SUPPORTED_ABIS.toList()) }.getOrNull()
    }
    fun dismissed(update: AppUpdate) = prefs.getInt("dismissed", 0) == update.versionCode
    fun dismiss(update: AppUpdate) { prefs.edit().putInt("dismissed", update.versionCode).apply() }

    fun download(update: AppUpdate) {
        require(update == available()) { "Проверьте наличие обновлений повторно" }
        val oldId = prefs.getLong("download_id", -1)
        if (pending() == update && oldId != -1L && downloadStatus() != DownloadManager.STATUS_FAILED) {
            scheduleDownloadCheck()
            return
        }
        if (oldId != -1L) downloads.remove(oldId)
        downloadedFile(update).delete()
        val request = DownloadManager.Request(Uri.parse(update.url))
            .setTitle("ЧСПЧ ${update.versionName}")
            .setDescription("Загрузка обновления")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, filename(update))
        val id = downloads.enqueue(request)
        prefs.edit().putLong("download_id", id).putString("pending", prefs.getString("available", null)).apply()
        scheduleDownloadCheck()
    }

    fun downloadStatus(): Int = downloads.query(DownloadManager.Query().setFilterById(prefs.getLong("download_id", -1)))?.use {
        if (it.moveToFirst()) it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else DownloadManager.STATUS_FAILED
    } ?: DownloadManager.STATUS_FAILED

    fun progress(): Int = downloads.query(DownloadManager.Query().setFilterById(prefs.getLong("download_id", -1)))?.use {
        if (!it.moveToFirst()) return@use 0
        val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        val bytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
    } ?: 0

    suspend fun verifiedApk(update: AppUpdate): File = withContext(Dispatchers.IO) {
        verificationMutex.withLock {
            require(update.versionCode > BuildConfig.VERSION_CODE)
            require(pending() == update && downloadStatus() == DownloadManager.STATUS_SUCCESSFUL) { "Обновление ещё не загружено" }
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            val apk = File(directory, filename(update))
            val part = File(directory, filename(update) + ".part")
            try {
                val source = downloadedFile(update)
                require(source.length() == update.sizeBytes) { "Размер APK не совпадает" }
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                source.inputStream().use { input -> part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        require(count <= update.sizeBytes) { "Размер APK не совпадает" }
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                } }
                require(count == update.sizeBytes && digest.digest().joinToString("") { "%02x".format(it) } == update.sha256) {
                    "Контрольная сумма APK не совпадает"
                }
                @Suppress("DEPRECATION")
                val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
                @Suppress("DEPRECATION")
                val installed = context.packageManager.getPackageInfo(context.packageName, flags)
                val archive = context.packageManager.getPackageArchiveInfo(part.path, flags) ?: error("Некорректный APK")
                @Suppress("DEPRECATION")
                val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
                require(archive.packageName == context.packageName && code == update.versionCode.toLong()) { "Пакет или версия APK не совпадают" }
                @Suppress("DEPRECATION")
                fun signatures(info: android.content.pm.PackageInfo): Set<String> {
                    val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
                    return signatures.orEmpty().map { it.toCharsString() }.toSet()
                }
                val trusted = signatures(installed)
                require(trusted.isNotEmpty() && trusted == signatures(archive)) { "Подпись APK не совпадает с установленным приложением" }
                apk.delete()
                require(part.renameTo(apk)) { "Не удалось подготовить APK" }
                apk
            } catch (error: Exception) {
                part.delete(); apk.delete()
                downloads.remove(prefs.getLong("download_id", -1))
                prefs.edit().remove("download_id").apply()
                throw error
            }
        }
    }

    fun canInstall() = context.packageManager.canRequestPackageInstalls()
    fun requestInstallPermission(activity: Activity) {
        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
    }
    fun install(activity: Activity, apk: File) {
        require(canInstall()) { "Разрешите установку обновлений из ЧСПЧ" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    fun notifyUpdate(update: AppUpdate, ready: Boolean = false, failed: Boolean = false) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (!ready && !failed && prefs.getInt("notified", 0) >= update.versionCode) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Обновления приложения", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).putExtra(EXTRA_SHOW_UPDATE, true)
        val pendingIntent = PendingIntent.getActivity(context, 481, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.icon)
            .setContentTitle(if (failed) "Не удалось загрузить обновление ЧСПЧ" else if (ready) "Обновление ЧСПЧ готово" else "Доступна ЧСПЧ ${update.versionName}")
            .setContentText(if (failed) "Нажмите, чтобы повторить загрузку" else if (ready) "Нажмите, чтобы установить" else "Нажмите, чтобы обновить приложение")
            .setContentIntent(pendingIntent).setAutoCancel(true)
            .addAction(0, if (ready) "Установить" else "Обновить", pendingIntent).build()
        try { manager.notify(NOTIFICATION_ID, notification); prefs.edit().putInt("notified", update.versionCode).apply() }
        catch (_: SecurityException) { /* Notification permission can be revoked while checking. */ }
    }
    fun isOurDownload(id: Long) = id >= 0 && id == prefs.getLong("download_id", -1)
    private fun scheduleDownloadCheck() {
        context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(DOWNLOAD_JOB_ID,
            ComponentName(context, UpdateJobService::class.java)).setMinimumLatency(10_000)
            .setBackoffCriteria(10_000, JobInfo.BACKOFF_POLICY_LINEAR).setPersisted(true).build())
    }
    fun schedule() {
        parseSaved("pending")?.takeIf { it.versionCode <= BuildConfig.VERSION_CODE }?.let { installed ->
            downloads.remove(prefs.getLong("download_id", -1))
            downloadedFile(installed).delete()
            File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
            prefs.edit().remove("pending").remove("download_id").apply()
            context.getSystemService(JobScheduler::class.java).cancel(DOWNLOAD_JOB_ID)
        }
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) == null) scheduler.schedule(JobInfo.Builder(JOB_ID,
            ComponentName(context, UpdateJobService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true).setPeriodic(6 * 60 * 60 * 1000L).build())
    }
    private fun filename(update: AppUpdate) = "chspch-${update.versionCode}-${update.abi}.apk"
    private fun downloadedFile(update: AppUpdate) = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), filename(update))
    companion object {
        const val EXTRA_SHOW_UPDATE = "show_app_update"
        const val JOB_ID = 48101
        const val DOWNLOAD_JOB_ID = 48103
        private const val NOTIFICATION_ID = 48102
        private const val CHANNEL = "app_updates"
        private val verificationMutex = Mutex()
    }
}
