package app.chspch.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppPicker(c: DesktopController, onClose: () -> Unit) {
    var apps by remember { mutableStateOf(emptyList<InstalledApp>()) }
    var selected by remember { mutableStateOf(c.apps.toSet()) }
    var query by remember { mutableStateOf("") }
    var generation by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var onlySelected by remember { mutableStateOf(false) }
    LaunchedEffect(generation) {
        loading = true; error = null
        try { apps = withContext(Dispatchers.IO) { InstalledApps.scan() } }
        catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = "Не удалось прочитать список приложений. Попробуйте обновить его или укажите файл вручную."
        } finally { loading = false }
    }
    val rows = (apps + c.apps.filter { p -> apps.none { p in it.paths } }.map { InstalledApp(c.appName(it), it) })
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    val visible = rows.filter { (!onlySelected || it.paths.any { path -> path in selected }) &&
        (it.name.contains(query.trim(), true) || it.path.substringAfterLast('/').substringAfterLast('\\').contains(query.trim(), true)) }
    Dialog(onCloseRequest=onClose,title="ЧСПЧ · Выбор приложений",state=rememberDialogState(width=760.dp,height=680.dp)) {
        PosterTheme(c.night) { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().posterTexture(c.night).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("ПРОГРАММЫ НА ЭТОМ ПК",fontFamily=Russo,fontSize=21.sp,color=MaterialTheme.colorScheme.primary)
                Text("Выбранные приложения будут работать через VPN в режиме TUN.",fontFamily=Playfair,fontSize=17.sp)
                Ornament()
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(query,{query=it},label={Text("Найти приложение")},singleLine=true,modifier=Modifier.weight(1f))
                    OutlinedButton(onClick={generation++},enabled=!loading){Text("Обновить")}
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("Выбрано файлов: ${selected.size}",fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                    Checkbox(onlySelected,onCheckedChange={onlySelected=it})
                    Text("Только выбранные",modifier=Modifier.clickable{onlySelected=!onlySelected})
                }
                if(loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Сканируем установленные приложения…") }
                error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(visible.isEmpty()&&!loading)item{Text(if(query.isNotBlank())"Ничего не найдено. Попробуйте другое название." else "В списке пока нет приложений.")}
                    items(visible,key={it.path}){app->
                        fun toggle(){selected=if(app.paths.all{it in selected})selected-app.paths.toSet() else selected+app.paths}
                        PosterPanel(Modifier.fillMaxWidth().clickable{toggle()}) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                Text(app.name.take(1).uppercase(),fontFamily=Russo,fontSize=24.sp,color=MaterialTheme.colorScheme.primary,modifier=Modifier.width(28.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.name,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
                                    Text(if(app.paths.size>1)"Файлов программы: ${app.paths.size}" else app.path,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if(app.paths.size>1)TextButton(onClick={expanded=if(app.path in expanded)expanded-app.path else expanded+app.path}){Text(if(app.path in expanded)"Скрыть" else "Файлы")}
                                TriStateCheckbox(if(app.paths.all{it in selected})ToggleableState.On else if(app.paths.any{it in selected})ToggleableState.Indeterminate else ToggleableState.Off,onClick={toggle()})
                            }
                            if(app.path in expanded)app.paths.forEach{path->
                                Row(Modifier.fillMaxWidth().clickable{selected=if(path in selected)selected-path else selected+path},verticalAlignment=Alignment.CenterVertically){
                                    Checkbox(path in selected,onCheckedChange={selected=if(it)selected+path else selected-path})
                                    Text(path.substringAfterLast('/').substringAfterLast('\\'),style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                Text("Не нашли программу? После закрытия окна можно указать её файл вручную. Приложения в песочнице и отдельные вспомогательные процессы могут потребовать дополнительного правила.",style=MaterialTheme.typography.bodySmall)
                Ornament()
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    TextButton(onClick=onClose){Text("Отмена")}
                    Button(onClick={c.selectApps(selected.toList(),rows.flatMap{app->app.paths.map{it to app.name}}.toMap());onClose()},enabled=!loading&&!c.busy&&!c.speedBusy){Text("Применить · ${selected.size}")}
                }
            }
        } }
    }
}
