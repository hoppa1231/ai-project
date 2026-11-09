package app.chspch.client.update

import android.app.Activity
import android.app.DownloadManager
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun AppUpdateHost(request: Int) {
    val context = LocalContext.current
    val activity = context as Activity
    val updater = remember { AppUpdater(context) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visible by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf(updater.pending() ?: updater.available()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf<File?>(null) }
    var lastCheck by remember { mutableLongStateOf(0) }

    fun check(manual: Boolean) {
        if (busy) { if (manual) visible = true; return }
        if (!manual && System.currentTimeMillis() - lastCheck < 30 * 60 * 1000) return
        busy = true
        if (manual) { visible = true; message = "Проверяем обновления…" }
        scope.launch {
            try {
                val found = updater.check()
                if (found != update) { ready = null; downloading = false }
                update = found
                lastCheck = System.currentTimeMillis()
                val candidate = update
                message = if (candidate == null) "Установлена последняя версия" else candidate.notes
                if (candidate != null) {
                    updater.notifyUpdate(candidate)
                    if (manual || !updater.dismissed(candidate)) visible = true
                    if (updater.pending() == candidate) downloading = true
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (manual) message = "Не удалось проверить обновления. Попробуйте позже" }
            finally { busy = false }
        }
    }
    DisposableEffect(lifecycle) {
        updater.schedule()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) check(false) }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) check(false)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(request) { if (request > 0) check(true) }
    LaunchedEffect(downloading, update) {
        val candidate = update ?: return@LaunchedEffect
        if (!downloading) return@LaunchedEffect
        try {
            while (true) {
                progress = updater.progress()
                when (updater.downloadStatus()) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        message = "Проверяем подпись и целостность…"
                        ready = updater.verifiedApk(candidate)
                        updater.notifyUpdate(candidate, ready = true)
                        message = "Обновление загружено и проверено. Для установки Android запросит подтверждение"
                        break
                    }
                    DownloadManager.STATUS_FAILED -> error("Загрузка не завершена. Нажмите «Скачать» для повтора")
                    DownloadManager.STATUS_PAUSED -> message = "Загрузка приостановлена. Ожидаем сеть…"
                    else -> message = "Скачиваем обновление: $progress%"
                }
                delay(1000)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { message = error.message ?: "Не удалось проверить обновление"; ready = null }
        finally { downloading = false }
    }
    if (visible) AlertDialog(
        onDismissRequest = { visible = false; update?.let(updater::dismiss) },
        title = { Text(update?.let { "ЧСПЧ ${it.versionName}" } ?: "Обновление ЧСПЧ") },
        text = { Text(message.ifBlank { update?.notes.orEmpty() }) },
        confirmButton = {
            val candidate = update
            if (candidate != null) TextButton(enabled = !busy && !downloading, onClick = {
                scope.launch {
                    try {
                        if (ready != null) {
                            if (!updater.canInstall()) {
                                message = "Разрешите установку из ЧСПЧ, затем вернитесь и нажмите «Установить»"
                                updater.requestInstallPermission(activity)
                            } else {
                                busy = true
                                // Recheck immediately before granting the installer access to the private copy.
                                val apk = updater.verifiedApk(candidate)
                                updater.install(activity, apk)
                            }
                        } else {
                            updater.download(candidate)
                            downloading = true
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { message = error.message ?: "Не удалось начать обновление" }
                    finally { busy = false }
                }
            }) { Text(if (ready != null) "Установить" else if (downloading) "$progress%" else "Скачать") }
            else TextButton(enabled = !busy, onClick = { check(true) }) { Text("Проверить") }
        },
        dismissButton = { TextButton(onClick = { visible = false; update?.let(updater::dismiss) }) { Text("Позже") } }
    )
}
