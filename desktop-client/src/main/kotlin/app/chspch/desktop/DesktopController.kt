package app.chspch.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Desktop
import java.net.*
import java.nio.file.Files
import java.nio.file.Path

class DesktopController(val store: StateStore, private val scope: CoroutineScope) {
    val api = DesktopApi(store, System.getenv("CHSPCH_API_URL") ?: "https://tech-supp-test.ru")
    val runtime = TunnelRuntime(store.directory)
    var busy by mutableStateOf(false); private set
    var connected by mutableStateOf(false); private set
    var desiredConnected by mutableStateOf(false); private set
    var status by mutableStateOf("Загружаем серверы…"); private set
    var nodes by mutableStateOf(emptyList<Node>()); private set
    var selected by mutableStateOf<Node?>(null)
    var mode by mutableStateOf(runCatching { TunnelMode.valueOf(store.data.optString("mode", "TUN")) }.getOrDefault(TunnelMode.TUN))
    var linked by mutableStateOf(store.data.optBoolean("linked")); private set
    var policy by mutableStateOf(JSONObject()); private set
    var login by mutableStateOf<TelegramLoginLinks?>(null); private set
    var tab by mutableStateOf(0)
    var night by mutableStateOf(store.data.optBoolean("night", true))
    var autoReconnect by mutableStateOf(store.data.optBoolean("autoReconnect", true))
    var closeToTray by mutableStateOf(store.data.optBoolean("closeToTray", false))
    var autostart by mutableStateOf(store.data.optBoolean("autostart", false)); private set
    var autoConnect by mutableStateOf(store.data.optBoolean("autoConnect", false))
    var foreground by mutableStateOf(true)
    var checks by mutableStateOf(listOf<Int?>(null, null, null)); private set
    var speedBusy by mutableStateOf(false); private set
    var speedCurrent by mutableStateOf(store.data.optJSONObject("speedCurrent")); private set
    var speedPrevious by mutableStateOf(store.data.optJSONObject("speedPrevious")); private set
    val apps = mutableStateListOf<String>().apply {
        val a = store.data.optJSONArray("processPaths") ?: JSONArray()
        for (i in 0 until a.length()) add(a.getString(i))
    }
    var update by mutableStateOf<DesktopRelease?>(null); private set
    var updateChecking by mutableStateOf(false); private set
    var updateDownloading by mutableStateOf(false); private set
    var updateProgress by mutableStateOf(0); private set
    var updateFile by mutableStateOf<Path?>(null); private set
    var updateStatus by mutableStateOf(""); private set
    fun checkUpdates(manual: Boolean = false) {
        if(updateChecking||updateDownloading)return
        updateChecking=true
        scope.launch {
            try {
                val result=withContext(Dispatchers.IO){DesktopUpdates.check()}
                if(update?.version!=result?.version)updateFile=null
                update=result
                updateStatus=if(result==null)"Установлена последняя версия" else "Доступна версия ${result.version}"
            } catch(e:Exception){if(e is CancellationException)throw e;if(manual)updateStatus="Не удалось проверить обновления. Повторите позднее."}
            finally {updateChecking=false}
        }
    }
    fun downloadUpdate() {
        val release=update?:return
        if(updateDownloading)return
        updateDownloading=true;updateProgress=0
        scope.launch {
            try {
                val path=withContext(Dispatchers.IO){DesktopUpdates.download(release,store.directory.resolve("updates")){percent->scope.launch{updateProgress=percent}}}
                updateFile=path;updateStatus="Обновление скачано и проверено. Распакуйте архив и установите поверх текущей версии."
                openUpdateFolder()
            } catch(e:Exception){if(e is CancellationException)throw e;updateStatus="Загрузка не завершена: ${safeError(e)}"}
            finally {updateDownloading=false}
        }
    }
    fun openUpdateFolder() {
        updateFile?.let{runCatching{Desktop.getDesktop().open(it.parent.toFile())}.onFailure{updateStatus="Обновление сохранено: ${updateFile}"}}
    }
    fun beginUpdateChecks(){
        checkUpdates()
        scope.launch{while(isActive){delay(6*60*60*1000L);checkUpdates()}}
    }
    val binary: Path = findCore()
    private var loginDeadline = 0L
    private var reconnectAt = 0L
    private var reconnectTries = 0
    private var probeGeneration = 0
    private var probeJob: Job? = null
    private var speedJob: Job? = null
    private val speedConnection = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)
    fun action(block: suspend () -> Unit) {
        if (busy || speedBusy) return
        busy = true
        scope.launch {
            try { block() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                status = safeError(e)
            } finally { connected = runtime.running; busy = false }
        }
    }
    suspend fun refresh() {
        val list = withContext(Dispatchers.IO) { api.nodes() }
        nodes = list
        selected = (if (connected) list.firstOrNull { it.id == selected?.id } else null)
            ?: list.firstOrNull { it.id == selected?.id && it.health == "HEALTHY" }
            ?: list.firstOrNull { it.id == store.data.optString("selectedNode") && it.health == "HEALTHY" }
            ?: list.firstOrNull { it.health == "HEALTHY" }
        policy = withContext(Dispatchers.IO) { api.policy() }
        if (!connected) status = if (selected == null) "Нет доступных серверов. Обновите список позднее." else "Готов к подключению"
    }
    fun saveSettings() {
        store.data.put("mode", mode.name).put("night", night).put("autoReconnect", autoReconnect)
            .put("closeToTray", closeToTray).put("processPaths", JSONArray(apps.toList())).put("autoConnect", autoConnect)
        store.save()
    }
    suspend fun connect() {
        desiredConnected = true; checks = listOf(null, null, null); probeGeneration++
        status = if (mode == TunnelMode.TUN) (if (runtime.authorized) "Подключаем TUN…" else "Подключаем TUN. Подтвердите системный запрос прав…") else "Подключаем прокси…"
        try {
            val effective = withContext(Dispatchers.IO) {
                check(Files.isRegularFile(binary)) { "VPN-ядро не найдено. Переустановите полный пакет ЧСПЧ." }
                val p = api.policy()
                withContext(Dispatchers.Main) { policy = p }
                val n = selected ?: error("Выберите доступный сервер")
                val issued = api.issue(n)
                try { runtime.start(binary, DesktopConfig.create(issued, p, api.baseUrl, mode, if (linked) apps.toList() else emptyList()).toString()) }
                catch (e: Exception) { if (!runtime.running) runCatching { api.revoke() }; throw e }
                val actual = issued.getJSONObject("node")
                Node(actual.getString("id"), actual.optString("name", n.name), "HEALTHY")
            }
            selected = nodes.firstOrNull { it.id == effective.id } ?: effective
            connected = runtime.running; reconnectTries = 0
            selected?.let { store.data.put("selectedNode", it.id) }; saveSettings()
            status = if (mode == TunnelMode.TUN) "Системный VPN подключён" else "Прокси подключён · 127.0.0.1:17890"
            requestChecks()
        } catch (e: Exception) { if(e is ElevationDenied) desiredConnected = false; reconnectAt = System.currentTimeMillis() + 15_000; throw e }
    }
    suspend fun disconnect() {
        desiredConnected = false; probeGeneration++
        withContext(Dispatchers.IO) { runtime.close() }
        connected = false; checks = listOf(null, null, null); status = "Отключено"
        withContext(Dispatchers.IO) { api.revoke() }
    }
    fun toggle() { if (speedBusy) cancelSpeed(); action { if (runtime.running) disconnect() else { try { connect() } catch (e: Exception) { desiredConnected = false; throw e } } } }
    private fun requestChecks() {
        if (probeJob?.isActive == true) return
        val generation = probeGeneration; val activeMode = mode
        probeJob = scope.launch {
            val results = coroutineScope {
                listOf("https://ya.ru", "https://www.google.com/generate_204", "https://telegram.org")
                    .map { url -> async(Dispatchers.IO) { probe(url, activeMode) } }.awaitAll()
            }
            if (generation == probeGeneration && runtime.running) checks = results
        }
    }
    fun startLogin() = action {
        login = TelegramLoginLinks.parse(withContext(Dispatchers.IO) { api.startLogin() })
        loginDeadline = System.currentTimeMillis() + 300_000
        openLogin(false)
    }
    fun openLogin(web: Boolean) {
        val links = login ?: return
        status = if (runCatching { Desktop.getDesktop().browse(if (web) links.web else links.app) }.isSuccess)
            "Нажмите «Старт» в открывшемся Telegram. Подтверждение проверяется автоматически."
            else "Telegram не открылся. Нажмите «Через браузер»."
    }
    suspend fun pollLogin() {
        val links = login ?: return
        if (System.currentTimeMillis() > loginDeadline) { login = null; status = "Ссылка истекла. Начните вход заново."; return }
        if (!withContext(Dispatchers.IO) { api.pollLogin(links.challengeId) }) return
        linked = true; login = null; desiredConnected = false; probeGeneration++
        withContext(Dispatchers.IO) { runtime.close() }; connected = false
        val cleanup = withContext(Dispatchers.IO) { runCatching { api.revoke() }.isSuccess }
        refresh()
        status = if (cleanup) "Вход выполнен. Выберите сервер и подключитесь." else "Вход выполнен. Повторите отзыв гостевого доступа в настройках."
    }
    suspend fun refreshPolicy() {
        policy = withContext(Dispatchers.IO) { api.policy() }
        status = "Список правил обновлён" + if (connected) ". Для применения изменений с другого устройства переподключите VPN." else ""
    }
    fun addWebsite(text: String, onSaved: () -> Unit = {}) = action {
        check(linked) { "Для изменения сервисов войдите через Telegram" }
        val destination = SharedWebsiteParser.parse(if (text.contains("://")) text else "https://$text")
        val updated = withContext(Dispatchers.IO) { api.policy() }
        val rules = updated.getJSONArray("routeRules")
        val existing = (0 until rules.length()).map(rules::getJSONObject).firstOrNull {
            it.optString("matchType") == destination.matchType && it.optJSONArray("values")?.toString() == JSONArray().put(destination.value).toString()
        }
        if (existing != null) existing.put("enabled", true)
        else rules.put(JSONObject().put("source", "USER").put("name", destination.value).put("enabled", true)
            .put("priority", 10000 + rules.length()).put("matchType", destination.matchType)
            .put("values", JSONArray().put(destination.value)).put("action", "VPN"))
        policy = withContext(Dispatchers.IO) { api.updatePolicy(updated) }
        onSaved()
        applyPolicyChange()
    }
    fun editRule(index: Int, enabled: Boolean, remove: Boolean = false) = action {
        check(linked)
        val updated = JSONObject(policy.toString()); val rules = updated.getJSONArray("routeRules")
        check(rules.getJSONObject(index).optBoolean("editable", true))
        if (remove) rules.remove(index) else rules.getJSONObject(index).put("enabled", enabled)
        policy = withContext(Dispatchers.IO) { api.updatePolicy(updated) }
        applyPolicyChange()
    }
    var appNames by mutableStateOf(store.data.optJSONObject("processAppNames") ?: JSONObject()); private set
    fun appName(path: String): String = appNames.optString(path).ifBlank { Path.of(path).fileName.toString() }
    fun selectApps(paths: List<String>, names: Map<String,String>) = action {
        check(linked) { "Сначала войдите через Telegram" }
        val resolved = withContext(Dispatchers.IO) {
            paths.distinct().map { value ->
                if (value in apps) value else Path.of(value).toRealPath().also {
                    check(Files.isRegularFile(it)) { "Программа больше не установлена. Обновите список." }
                }.toString()
            }
        }
        apps.clear(); apps.addAll(resolved)
        appNames = JSONObject().also { obj -> resolved.forEach { obj.put(it, names[it] ?: appName(it)) } }
        store.data.put("processAppNames", appNames)
        saveSettings(); applyPolicyChange()
    }
    fun addApp(path: Path) = action {
        check(linked) { "Сначала войдите через Telegram" }
        val resolved = withContext(Dispatchers.IO) { path.toRealPath().toString() }
        if (resolved !in apps) apps.add(resolved)
        saveSettings(); applyPolicyChange()
    }
    fun removeApp(path: String) = action { apps.remove(path); saveSettings(); applyPolicyChange() }
    private suspend fun applyPolicyChange() {
        if (runtime.running) { withContext(Dispatchers.IO) { runtime.close() }; connected = false; connect() }
        else status = "Правила сохранены"
    }
    fun beginMonitoring() {
        scope.launch {
            var ticks = 0
            var lastTick = System.currentTimeMillis()
            while (isActive) {
                delay(2000); ticks++
                val now = System.currentTimeMillis(); val resumed = now - lastTick > 20_000; lastTick = now
                if (resumed && connected && autoReconnect && !busy && !speedBusy) {
                    action { withContext(Dispatchers.IO) { runtime.close() }; connected = false; refresh(); connect() }
                    continue
                }
                if (connected && !runtime.running && speedBusy) cancelSpeed()
                if (connected && !runtime.running) { connected = false; status = "Соединение потеряно"; reconnectAt = System.currentTimeMillis() + 3000 }
                if (busy || speedBusy) continue
                if (login != null) action { pollLogin() }
                else if (desiredConnected && !connected && autoReconnect && System.currentTimeMillis() >= reconnectAt && reconnectTries < 3) {
                    reconnectTries++; reconnectAt = System.currentTimeMillis() + 30_000
                    action { refresh(); connect() }
                } else if (connected && foreground && ticks % 5 == 0) requestChecks()
            }
        }
    }
    fun changeAutostart(enabled: Boolean) = action {
        withContext(Dispatchers.IO) { DesktopIntegration.autostart(enabled) }
        autostart = enabled; store.data.put("autostart", enabled); store.save()
        status = if (enabled) "Запуск при входе в систему включён" else "Автозапуск отключён"
    }
    fun addShortcut() = action { withContext(Dispatchers.IO) { DesktopIntegration.desktopShortcut() }; status = "Ярлык ЧСПЧ добавлен в меню приложений" }
    fun retryRevoke() = action { withContext(Dispatchers.IO) { api.revoke() }; status = "Отзыв доступа подтверждён" }
    fun speedTest() {
        if (busy || speedBusy || speedJob?.isCompleted == false || !connected || !linked) return
        speedBusy = true
        speedJob = scope.launch {
            try {
                val context = currentCoroutineContext()
                val result = withContext(Dispatchers.IO) { measureSpeed(mode, { speedConnection.set(it) }, { context.ensureActive() }) }
                speedPrevious = speedCurrent; speedCurrent = result
                store.data.put("speedPrevious", speedPrevious).put("speedCurrent", result); store.save()
                status = "Замер завершён · speed.cloudflare.com, по текущей политике"
            } catch (e: Exception) { if (e !is CancellationException) status = safeError(e) }
            finally { speedConnection.getAndSet(null)?.disconnect(); speedBusy = false }
        }
    }
    fun cancelSpeed() { speedJob?.cancel(); speedConnection.getAndSet(null)?.disconnect(); speedBusy = false; status = "Замер отменён" }
    suspend fun shutdown() { cancelSpeed(); desiredConnected = false; withContext(Dispatchers.IO) { runtime.shutdown(); runCatching { api.revoke() } }; saveSettings() }
}
internal fun findCore(): Path {
    System.getenv("CHSPCH_SING_BOX")?.let { return Path.of(it).toAbsolutePath() }
    val win = System.getProperty("os.name").startsWith("Windows")
    val name = if (win) "sing-box.exe" else "sing-box"
    val dir = System.getProperty("compose.application.resources.dir")
    return dir?.let { Path.of(it, "core", name) } ?: Path.of("packaging", if (win) "windows-x64" else "linux-x64", "core", name).toAbsolutePath()
}
internal fun safeError(e: Exception): String = when (e) {
    is ApiFailure -> e.message!!
    is java.io.IOException -> "Сетевая ошибка (${e.javaClass.simpleName}). Проверьте подключение."
    else -> e.message?.take(220) ?: "Не удалось выполнить действие"
}
internal fun connection(url: String, mode: TunnelMode): HttpURLConnection = (URI(url).toURL().openConnection(
    if (mode == TunnelMode.PROXY) Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", DesktopConfig.PORT)) else Proxy.NO_PROXY) as HttpURLConnection)
    .apply { connectTimeout = 6000; readTimeout = 6000; instanceFollowRedirects = false }
internal fun probe(url: String, mode: TunnelMode): Int = runCatching {
    val c = connection(url, mode); try { c.requestMethod = "HEAD"; c.responseCode } finally { c.disconnect() }
}.getOrDefault(-1)
internal fun probeTelegram(): Int = probe("https://telegram.org", TunnelMode.PROXY)
private fun measureSpeed(mode: TunnelMode, track: (HttpURLConnection) -> Unit, cancelled: () -> Unit): JSONObject {
    val pingStart = System.nanoTime()
    check(probe("https://speed.cloudflare.com/__down?bytes=0", mode) in 200..299) { "Сервис измерения недоступен" }
    val ping = (System.nanoTime() - pingStart) / 1_000_000.0
    val down = connection("https://speed.cloudflare.com/__down?bytes=10000000", mode).apply { readTimeout = 30000 }
    track(down); cancelled()
    val start = System.nanoTime()
    val bytes = try { down.inputStream.use { input -> val buf=ByteArray(65536); var total=0L; while (total < 10_000_000) { cancelled(); val n=input.read(buf, 0, minOf(buf.size.toLong(), 10_000_000-total).toInt()); if(n<0) break; total+=n }; check(total == 10_000_000L) { "Сервис вернул неполный замер" }; total } } finally { down.disconnect() }
    val download = bytes * 8.0 / (System.nanoTime() - start) * 1000.0
    val up = connection("https://speed.cloudflare.com/__up", mode).apply { requestMethod="POST"; doOutput=true; readTimeout=30000; setFixedLengthStreamingMode(2_000_000) }
    track(up); cancelled()
    val upStart = System.nanoTime()
    try { up.outputStream.use { output -> repeat(20) { cancelled(); output.write(ByteArray(100000)) } }; check(up.responseCode in 200..299) { "Ошибка измерения отдачи" } } finally { up.disconnect() }
    return JSONObject().put("download", download).put("upload", 16_000_000.0 / (System.nanoTime()-upStart)*1000.0).put("ping", ping)
}
