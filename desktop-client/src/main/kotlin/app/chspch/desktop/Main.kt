package app.chspch.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.awt.SystemTray
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import javax.swing.JFileChooser
import javax.swing.JOptionPane

fun main() {
    val store=StateStore()
    val channel=FileChannel.open(store.directory.resolve("desktop.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)
    val lock=channel.tryLock()
    if(lock==null){JOptionPane.showMessageDialog(null,"ЧСПЧ уже запущен. Откройте окно через значок в трее.");channel.close();return}
    try { application {
        val scope=rememberCoroutineScope()
        val c=remember{DesktopController(store,scope)}
        var visible by remember{mutableStateOf(true)}
        var exiting by remember{mutableStateOf(false)}
        val state=rememberWindowState(width=1080.dp,height=790.dp)
        val tray=SystemTray.isSupported()
        val trayState=rememberTrayState()
        LaunchedEffect(c.update?.version){
            val release=c.update
            if(tray&&release!=null&&store.data.optString("updateNotified")!=release.version){
                trayState.sendNotification(Notification("Обновление ЧСПЧ", "Доступна версия ${release.version}. Откройте приложение для загрузки."))
                store.data.put("updateNotified",release.version);store.save()
            }
        }
        fun exit(){if(c.busy||c.speedBusy||exiting)return;exiting=true;scope.launch{try{c.shutdown();exitApplication()}catch(_:Exception){exiting=false;visible=true}}}
        DisposableEffect(Unit){val hook=Thread{runCatching{c.runtime.shutdown()}};Runtime.getRuntime().addShutdownHook(hook);onDispose{runCatching{Runtime.getRuntime().removeShutdownHook(hook)};runCatching{c.runtime.shutdown()}}}
        LaunchedEffect(Unit){c.action{c.refresh();if(c.autoConnect)c.connect()};c.beginMonitoring();c.beginUpdateChecks()}
        LaunchedEffect(visible,state.isMinimized){c.foreground=visible&&!state.isMinimized}
        if(tray) Tray(state=trayState,icon=painterResource("images/icon.png"),tooltip=if(c.connected)"ЧСПЧ · подключено" else "ЧСПЧ",onAction={visible=true;state.isMinimized=false}){
            Item("Открыть ЧСПЧ",onClick={visible=true;state.isMinimized=false})
            Item(if(c.connected)"Отключить" else "Подключить",enabled=!c.busy&&!exiting,onClick={c.toggle()})
            Separator();Item("Выйти",enabled=!c.busy&&!c.speedBusy&&!exiting,onClick={exit()})
        }
        Window(visible=visible,onCloseRequest={if(tray&&c.closeToTray)visible=false else exit()},title="ЧСПЧ · ВПН трудящихся",state=state,icon=painterResource("images/icon.png")){
            PosterTheme(c.night){Surface(Modifier.fillMaxSize()){
                BoxWithConstraints(Modifier.fillMaxSize().posterTexture(c.night)){
                    val compact=maxWidth<850.dp
                    Column(Modifier.fillMaxSize().padding(if(compact)14.dp else 24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
                            Text("ЧСПЧ",fontFamily=Russo,fontSize=32.sp,color=MaterialTheme.colorScheme.primary)
                            Text("ВПН ТРУДЯЩИХСЯ",fontFamily=Playfair,fontStyle=FontStyle.Italic,fontSize=19.sp,modifier=Modifier.weight(1f))
                            Text(if(c.linked)"ПРОПУСК ПОЛУЧЕН" else "ГОСТЕВОЙ ПРОПУСК",fontFamily=PtSans,fontSize=11.sp,color=MaterialTheme.colorScheme.primary)
                        }
                        Ornament()
                        ScrollableTabRow(selectedTabIndex=c.tab,edgePadding=0.dp,containerColor=Color.Transparent){
                            listOf("СВЯЗЬ","СЕРВЕРЫ","МАРШРУТЫ","СКОРОСТЬ","НАСТРОЙКИ").forEachIndexed{i,title->Tab(selected=c.tab==i,onClick={c.tab=i},text={Text(title,fontFamily=Russo,fontSize=12.sp)})}
                        }
                        if(c.update!=null)UpdatePanel(c)
                        Box(Modifier.fillMaxWidth().height(4.dp)) {
                            if(c.busy||exiting)LinearProgressIndicator(Modifier.fillMaxSize())
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)){
                            when(c.tab){0->Home(c,compact);1->Servers(c);2->Routes(c);3->Speed(c);4->Settings(c)}
                        }
                        HorizontalDivider(color=MaterialTheme.colorScheme.outline)
                        Text(c.status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=2)
                    }
                }
            }}
        }
    } } finally{lock.release();channel.close()}
}
@Composable private fun Home(c:DesktopController,compact:Boolean){
    if(compact){Power(c);ConnectionDetails(c)}else Row(horizontalArrangement=Arrangement.spacedBy(26.dp)){
        Box(Modifier.weight(1f)){Power(c)};Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(18.dp)){ConnectionDetails(c)}
    }
}
@Composable private fun Power(c:DesktopController){
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("ВПН\nТРУДЯЩИХСЯ",fontFamily=Playfair,fontWeight=FontWeight.Bold,fontStyle=FontStyle.Italic,fontSize=38.sp,lineHeight=40.sp,textAlign=TextAlign.Center,color=MaterialTheme.colorScheme.primary)
        Text(if(c.connected)"— да здравствуетъ свободный трафикъ! —" else "— связь обесточена —",fontFamily=Playfair,fontStyle=FontStyle.Italic,fontSize=13.sp)
        PowerSwitch(c.connected,!c.busy&&!c.speedBusy){c.toggle()}
        Button(onClick={c.toggle()},enabled=!c.busy,modifier=Modifier.width(230.dp)){Text(if(c.connected)"РАЗОМКНУТЬ СВЯЗЬ" else "ПОДКЛЮЧИТЬ")}
        Text("Нажмите на рубильникъ",fontFamily=Playfair,fontStyle=FontStyle.Italic,fontSize=12.sp)
    }
}
@Composable private fun ConnectionDetails(c:DesktopController){
    PosterPanel(Modifier.fillMaxWidth()){
        Text(if(c.connected)"НА СВЯЗИ" else if(c.busy)"СОЕДИНЯЕМ" else "НЕТ СВЯЗИ",fontFamily=Russo,fontSize=26.sp,color=MaterialTheme.colorScheme.primary)
        Text(if(c.mode==TunnelMode.TUN)"СИСТЕМНЫЙ VPN · VLESS" else "ЛОКАЛЬНЫЙ ПРОКСИ · VLESS",letterSpacing=1.sp,fontSize=11.sp)
        Text("По умолчанию — напрямую. Через VPN — выбранные сервисы и программы.")
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
            listOf("russian","foreign","blocked").forEachIndexed{i,icon->
                val code=c.checks[i];val color=when{code==null->MaterialTheme.colorScheme.outline;code in 200..399->Color(0xFF83B980);code==403||code==429->Gold;else->Color(0xFFCF6760)}
                Column(horizontalAlignment=Alignment.CenterHorizontally){
                    Image(painterResource("images/ic_service_$icon.png"),contentDescription=listOf("ya.ru","Google","Telegram")[i]+": "+(code?.let{"HTTP $it"}?:"не проверено"),modifier=Modifier.size(44.dp),colorFilter=androidx.compose.ui.graphics.ColorFilter.tint(color))
                    Text("●",color=color,fontSize=23.sp)
                }
            }
        }
        Text("Индикаторы: ya.ru, Google, Telegram. Ответ 403/429 не доказывает блокировку.",style=MaterialTheme.typography.bodySmall)
    }
    PosterPanel(Modifier.fillMaxWidth().clickable{c.tab=1}){
        Text("ПУНКТ НАЗНАЧЕНИЯ",fontFamily=Russo,fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
        Text(c.selected?.name?:"Выберите сервер",style=MaterialTheme.typography.headlineMedium)
        Text("Сменить сервер →",style=MaterialTheme.typography.bodySmall)
    }
    Account(c)
}
@Composable private fun Account(c:DesktopController){
    PosterPanel(Modifier.fillMaxWidth()){
        Text(if(c.linked)"ПРОПУСК ДЕЙСТВИТЕЛЕН" else "ПОЛУЧИТЬ ПРОПУСК",fontFamily=Russo,fontSize=14.sp,color=MaterialTheme.colorScheme.primary)
        Text(if(c.linked)"Аккаунт Telegram подключён. Доступны обычные серверы и свои маршруты." else "Гостевое подключение открывает Telegram. Войдите, чтобы выбрать остальные сервисы.")
        OutlinedButton(onClick={c.startLogin()},enabled=!c.busy&&!c.speedBusy){Text(if(c.linked)"Войти повторно" else "Войти через Telegram")}
        c.login?.let { links ->
            val clipboard = LocalClipboardManager.current
            var copied by remember(links) { mutableStateOf(false) }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                TextButton(onClick={c.openLogin(false)},enabled=!c.loginOpening){Text("Telegram")}
                TextButton(onClick={c.openLogin(true)},enabled=!c.loginOpening){Text("Через браузер")}
            }
            Text("Если ничего не открылось, скопируйте ссылку и вставьте в браузер. Затем нажмите «Старт» в боте.",style=MaterialTheme.typography.bodySmall)
            SelectionContainer { Text(links.web.toASCIIString(),style=MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={
                    copied = runCatching { clipboard.setText(AnnotatedString(links.web.toASCIIString())) }.isSuccess
                }) { Text(if(copied) "Ссылка скопирована" else "Копировать ссылку") }
                TextButton(onClick={c.action{c.pollLogin()}},enabled=!c.busy){Text("Проверить вход")}
            }
        }
    }
}
@Composable private fun Servers(c:DesktopController){
    Row(verticalAlignment=Alignment.CenterVertically){Text("ПУНКТЫ СВЯЗИ",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.weight(1f));OutlinedButton(onClick={c.action{c.refresh()}},enabled=!c.busy){Text("Обновить")}}
    c.nodes.forEach{node->PosterPanel(Modifier.fillMaxWidth().clickable(enabled=!c.busy&&node.health=="HEALTHY"){c.action{val active=c.connected;if(active)c.disconnect();c.selected=node;if(active)c.connect()}}){
        Row(verticalAlignment=Alignment.CenterVertically){RadioButton(c.selected?.id==node.id,onClick=null);Column(Modifier.weight(1f)){Text(node.name,fontFamily=Playfair,fontSize=23.sp);Text(if(node.health=="HEALTHY")"ГОТОВ К РАБОТЕ" else "НЕДОСТУПЕН",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)}}
    }}
    if(c.nodes.isEmpty())Text("Список пока пуст. Проверьте соединение и нажмите «Обновить».")
}
@Composable private fun Routes(c:DesktopController){
    LaunchedEffect(Unit){c.action{c.refreshPolicy()}}
    Row(verticalAlignment=Alignment.CenterVertically){
        Text("КУДА НАПРАВИТЬ ТРАФИКЪ",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.weight(1f))
        OutlinedButton(onClick={c.action{c.refreshPolicy()}},enabled=!c.busy){Text("Обновить")}
        if(c.connected)TextButton(onClick={c.action{c.disconnect();c.connect()}},enabled=!c.busy){Text("Применить к туннелю")}
    }
    var details by remember{mutableStateOf<JSONObject?>(null)}
    details?.let{rule->RuleContents(rule){details=null}}
    Text("Остальной трафик идёт напрямую. Домены .ru, .su и .рф нельзя добавлять в VPN.")
    if(!c.linked)Account(c)
    var site by remember{mutableStateOf("")}
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(site,{site=it},label={Text("Сайт или IP")},singleLine=true,enabled=c.linked&&!c.busy,modifier=Modifier.weight(1f));Button(onClick={c.addWebsite(site){site=""}},enabled=c.linked&&!c.busy&&site.isNotBlank()){Text("Добавить")}}
    val rules=c.policy.optJSONArray("routeRules")
    if(rules!=null)for(i in 0 until rules.length()){
        val rule=rules.getJSONObject(i)
        if(rule.optString("matchType")=="APP_PACKAGE")continue
        PosterPanel(Modifier.fillMaxWidth()){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Column(Modifier.weight(1f)){Text(rule.optString("name"),fontWeight=FontWeight.Bold);Text(if(rule.optString("action")=="VPN")"Через VPN" else if(rule.optString("action")=="BLOCK")"Запретить" else "Напрямую",style=MaterialTheme.typography.bodySmall)}
                TextButton(onClick={details=rule}){Text("Состав (${rule.optJSONArray("values")?.length()?:0})")}
                Switch(rule.optBoolean("enabled",true),onCheckedChange={c.editRule(i,it)},enabled=c.linked&&!c.busy&&rule.optBoolean("editable",true))
                if(rule.optString("source")=="USER")TextButton(onClick={c.editRule(i,false,true)},enabled=!c.busy&&c.linked){Text("Убрать")}
            }
        }
    }
    Ornament();Text("ПРОГРАММЫ НА ЭТОМ ПК",fontFamily=Russo,fontSize=16.sp)
    Text("Отметьте приложения, которые должны подключаться через VPN. Выбор действует в системном режиме TUN и хранится на этом ПК.",style=MaterialTheme.typography.bodySmall)
    var choosingApps by remember{mutableStateOf(false)}
    if(choosingApps)AppPicker(c){choosingApps=false}
    c.apps.toList().groupBy{c.appName(it)}.forEach{(name,paths)->PosterPanel(Modifier.fillMaxWidth()){
        Row(verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){Text(name,fontWeight=FontWeight.Bold);Text("Через VPN · файлов: ${paths.size}",style=MaterialTheme.typography.bodySmall)}
            TextButton(onClick={c.selectApps(c.apps.filter{it !in paths},c.apps.associateWith{c.appName(it)})},enabled=!c.busy){Text("Убрать")}
        }
    }}
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
        Button(enabled=c.linked&&!c.busy,onClick={choosingApps=true}){Text("Выбрать приложения")}
        TextButton(enabled=c.linked&&!c.busy,onClick={val picker=JFileChooser().apply{dialogTitle="Программа, которой нет в списке"};if(picker.showOpenDialog(null)==JFileChooser.APPROVE_OPTION)c.addApp(picker.selectedFile.toPath())}){Text("Указать файл…")}
    }

}
@Composable private fun RuleContents(rule:JSONObject,onClose:()->Unit){
    var query by remember{mutableStateOf("")}
    val values=rule.optJSONArray("values")
    val entries=(0 until (values?.length()?:0)).map{values!!.getString(it)}
    AlertDialog(onDismissRequest=onClose,title={Text(rule.optString("name"))},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(if(rule.optString("matchType")=="DOMAIN_SUFFIX")"Домен и его поддомены" else rule.optString("matchType"))
            OutlinedTextField(query,{query=it},label={Text("Поиск в правиле")},singleLine=true)
            val visible=entries.filter{it.contains(query.trim(),ignoreCase=true)}
            Text("Найдено ${visible.size} из ${entries.size}")
            SelectionContainer{Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())){
                if(visible.isEmpty())Text("Совпадений нет")
                visible.forEach{Text(it)}
            }}
        }
    },confirmButton={TextButton(onClick=onClose){Text("Закрыть")}})
}
@Composable private fun Speed(c:DesktopController){
    Text("СКОРОСТЬ СОЕДИНЕНИЯ",style=MaterialTheme.typography.headlineMedium)
    Text("Реальный замер до speed.cloudflare.com по текущим правилам маршрутизации. Он может расходовать около 12 МБ.")
    Button(onClick={c.speedTest()},enabled=c.connected&&c.linked&&!c.busy&&!c.speedBusy){Text(if(c.speedBusy)"Измеряем…" else "Начать замер")}
    Box(Modifier.fillMaxWidth().height(4.dp)) {
        if(c.speedBusy)LinearProgressIndicator(Modifier.fillMaxSize())
    }
    Box(Modifier.height(48.dp)) {
        if(c.speedBusy)TextButton(onClick={c.cancelSpeed()}){Text("Отменить замер")}
    }
    Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){DesktopSpeedGauge((c.speedCurrent?.optDouble("download",0.0)?:0.0).toFloat())}
    SpeedResult("ТЕКУЩИЙ РЕЗУЛЬТАТ",c.speedCurrent)
    if(c.speedPrevious!=null)SpeedResult("ПРЕДЫДУЩИЙ",c.speedPrevious)
}
@Composable private fun SpeedResult(title:String,result:JSONObject?){PosterPanel(Modifier.fillMaxWidth()){
    Text(title,fontFamily=Russo,fontSize=14.sp,color=MaterialTheme.colorScheme.primary)
    if(result==null)Text("Замеров пока нет")else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
        listOf("ПРИЁМ" to "${"%.1f".format(result.optDouble("download"))} Мбит/с","ОТДАЧА" to "${"%.1f".format(result.optDouble("upload"))} Мбит/с","ОТКЛИК" to "${"%.0f".format(result.optDouble("ping"))} мс").forEach{(label,value)->Column{Text(label,fontSize=11.sp);Text(value,fontFamily=Russo,fontSize=20.sp)}}
    }
}}
@Composable private fun Settings(c:DesktopController){
    Text("НАЛАДКА СВЯЗИ",style=MaterialTheme.typography.headlineMedium)
    PosterPanel(Modifier.fillMaxWidth()){
        Text("РЕЖИМ РАБОТЫ",fontFamily=Russo,fontSize=14.sp)
        TunnelMode.entries.forEach{mode->Row(verticalAlignment=Alignment.CenterVertically){RadioButton(c.mode==mode,onClick={c.mode=mode;c.saveSettings()},enabled=!c.busy&&!c.connected);Text(if(mode==TunnelMode.TUN)"Системный VPN · все приложения используют правила" else "Локальный прокси · 127.0.0.1:17890")}}
        Text("Для системного VPN появится штатный запрос прав администратора. Интерфейс работает без повышенных прав.",style=MaterialTheme.typography.bodySmall)
    }
    PosterPanel(Modifier.fillMaxWidth()){
        SettingSwitch("Ночное оформление",c.night){c.night=it;c.saveSettings()}
        SettingSwitch("Запускать при входе в систему",c.autostart){c.changeAutostart(it)}
        SettingSwitch("Подключаться при запуске ЧСПЧ",c.autoConnect){c.autoConnect=it;c.saveSettings()}
        SettingSwitch("Восстанавливать соединение после обрыва",c.autoReconnect){c.autoReconnect=it;c.saveSettings()}
        SettingSwitch("Сворачивать в трей при закрытии окна",c.closeToTray){c.closeToTray=it;c.saveSettings()}
    }
    Account(c)
    if(System.getProperty("os.name").startsWith("Linux"))OutlinedButton(onClick={c.addShortcut()},enabled=!c.busy){Text("Добавить в меню приложений")}
    OutlinedButton(onClick={c.retryRevoke()},enabled=!c.busy&&!c.connected){Text("Повторить отзыв неиспользуемого доступа")}
    Text("ЧСПЧ $DESKTOP_VERSION · sing-box 1.14.4",style=MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick={c.checkUpdates(true)},enabled=!c.updateChecking){Text(if(c.updateChecking)"Проверяем…" else "Проверить обновления")}
    Text(c.updateStatus,style=MaterialTheme.typography.bodySmall)
    Text("Проверка при запуске и каждые 6 часов, пока ЧСПЧ работает. Обновления не требуют входа или VPN.",style=MaterialTheme.typography.bodySmall)
}
@Composable private fun UpdatePanel(c:DesktopController){
    val release=c.update?:return
    PosterPanel(Modifier.fillMaxWidth()){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Column(Modifier.weight(1f)){
                Text("ОБНОВЛЕНИЕ ЧСПЧ · ${release.version}",fontFamily=Russo,fontSize=14.sp)
                Text(release.notes,style=MaterialTheme.typography.bodySmall,maxLines=2)
            }
            Button(onClick={if(c.updateFile!=null)c.openUpdateFolder() else c.downloadUpdate()},enabled=!c.updateDownloading){
                Text(if(c.updateDownloading)"${c.updateProgress}%" else if(c.updateFile!=null)"Открыть папку" else "Скачать")
            }
        }
        if(c.updateStatus.isNotBlank())Text(c.updateStatus,style=MaterialTheme.typography.bodySmall,maxLines=2)
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if(c.updateDownloading)LinearProgressIndicator(c.updateProgress/100f,Modifier.fillMaxSize())
        }
        if(c.updateFile!=null)Text("Распакуйте архив. Закройте ЧСПЧ и запустите "+if(System.getProperty("os.name").startsWith("Windows"))"Install.exe. Вход и настройки сохранятся." else "install-linux.sh. Вход и настройки сохранятся.",style=MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun SettingSwitch(label:String,value:Boolean,onChange:(Boolean)->Unit){Row(verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(value,onChange)}}

@Composable private fun DesktopSpeedGauge(speed: Float) {
    val progress by androidx.compose.animation.core.animateFloatAsState((speed/100f).coerceIn(0f,1f),label="speed")
    Box(Modifier.size(250.dp),contentAlignment=Alignment.Center){
        Image(painterResource("images/speedomentr.png"),null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
        Image(painterResource("images/arrow.png"),null,Modifier.size(130.dp).then(Modifier.graphicsLayer {
            transformOrigin=androidx.compose.ui.graphics.TransformOrigin(.5f,.824f)
            rotationZ=-135f+progress*270f
            translationY=-130.dp.toPx()*(.824f-.5f)
        }),contentScale=ContentScale.Fit)
    }
}
