package app.chspch.desktop

import org.json.JSONArray
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class InstalledApp(val name: String, val path: String, val paths: List<String> = listOf(path))

/** Reads launchers, never starts applications or crawls user documents. */
object InstalledApps {
    fun scan(): List<InstalledApp> = if (System.getProperty("os.name").startsWith("Windows")) windows() else linux()

    internal fun commandWords(command: String): List<String> {
        val result = mutableListOf<String>(); val word = StringBuilder()
        var quote: Char? = null; var escaped = false
        for (c in command) {
            if (escaped) { word.append(c); escaped = false }
            else if (c == '\\' && quote != '\'') escaped = true
            else if (quote != null) { if (c == quote) quote = null else word.append(c) }
            else if (c == '"' || c == '\'') quote = c
            else if (c.isWhitespace()) { if (word.isNotEmpty()) { result.add(word.toString()); word.clear() } }
            else word.append(c)
        }
        if (escaped || quote != null) return emptyList()
        if (word.isNotEmpty()) result.add(word.toString())
        return result
    }
    internal fun executable(command: String, searchPath: List<Path>): String? {
        val words = commandWords(command)
        var index = 0
        if (words.firstOrNull()?.substringAfterLast('/') == "env") {
            index++
            while (index < words.size && (words[index].contains('=') || words[index] == "--")) index++
        }
        val token = words.getOrNull(index) ?: return null
        // Selecting the sandbox launcher would route every Flatpak/Snap app.
        if (token.substringAfterLast('/') in setOf("flatpak", "snap", "sh", "bash", "python", "python3", "java", "wine", "xdg-open", "gio")) return null
        val candidates = if (token.startsWith('/')) listOf(Path.of(token)) else searchPath.map { it.resolve(token) }
        return candidates.firstNotNullOfOrNull { p -> runCatching {
            if (Files.isRegularFile(p) && Files.isExecutable(p)) linuxProcessPath(p) else null
        }.getOrNull() }
    }
    private fun linuxProcessPath(path: Path): String? {
        val real = path.toRealPath()
        val script = Files.newInputStream(real).use { it.readNBytes(2).contentEquals(byteArrayOf(35,33)) }
        if (!script) return real.toString()
        // Resolve a plain exec wrapper without running shell code. A script itself
        // is not the process making network connections.
        val lines = Files.newBufferedReader(real).use { reader -> reader.lineSequence().take(200).toList() }
        val targets = lines.map(String::trim).filter { it.startsWith("exec ") }.mapNotNull { line ->
            commandWords(line.removePrefix("exec ")).firstOrNull()?.takeIf { it.startsWith('/') && '$' !in it }
        }.distinct()
        if (targets.size == 1) {
            val target = Path.of(targets.single())
            if (target != real && Files.isRegularFile(target) && Files.isExecutable(target)) return target.toRealPath().toString()
        }
        if (path.fileName.toString() in setOf("discord", "discord-canary", "discord-ptb")) {
            val folder = path.fileName.toString()
            val executable = when(folder) { "discord-canary" -> "DiscordCanary"; "discord-ptb" -> "DiscordPTB"; else -> "Discord" }
            val config = System.getenv("XDG_CONFIG_HOME")?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".config")
            val target = config.resolve(folder).resolve(executable)
            if (Files.isRegularFile(target) && Files.isExecutable(target)) return target.toRealPath().toString()
        }
        return null
    }
    internal fun linux(
        roots: List<Path> = linuxRoots(),
        searchPath: List<Path> = System.getenv("PATH").orEmpty().split(':').filter { it.startsWith('/') }.map(Path::of),
        locale: String = System.getenv("LANG").orEmpty().substringBefore('.').substringBefore('_')
    ): List<InstalledApp> {
        val seen = mutableSetOf<String>(); val result = mutableListOf<InstalledApp>()
        for (root in roots) {
            if (!Files.isDirectory(root)) continue
            Files.walk(root, 4).use { files -> files.filter { it.toString().endsWith(".desktop") && Files.isRegularFile(it) }.forEach { file ->
                val id = root.relativize(file).toString().replace('/', '-')
                if (!seen.add(id)) return@forEach
                runCatching {
                    val fields = linkedMapOf<String,String>(); var section = false
                    Files.readAllLines(file).forEach { line ->
                        if (line.startsWith('[')) section = line.trim() == "[Desktop Entry]"
                        else if (section && '=' in line && !line.startsWith('#')) fields[line.substringBefore('=')] = line.substringAfter('=')
                    }
                    if (fields["Type"] != "Application" || fields["Hidden"] == "true" || fields["NoDisplay"] == "true") return@runCatching
                    val name = fields["Name[$locale]"] ?: fields["Name"] ?: return@runCatching
                    val target = executable(fields["Exec"].orEmpty(), searchPath) ?: return@runCatching
                    if (name.contains("ЧСПЧ", true) || target.contains("/chspch/")) return@runCatching
                    result.add(InstalledApp(name, target))
                }
            } }
        }
        return unique(result)
    }
    private fun linuxRoots(): List<Path> {
        val home = Path.of(System.getProperty("user.home"))
        val data = System.getenv("XDG_DATA_HOME")?.takeIf { it.startsWith('/') }?.let(Path::of) ?: home.resolve(".local/share")
        return listOf(data.resolve("applications")) +
            (System.getenv("XDG_DATA_DIRS") ?: "/usr/local/share:/usr/share").split(':').filter { it.startsWith('/') }.map { Path.of(it, "applications") }
    }
    private fun windows(): List<InstalledApp> {
        val script = """
            ${'$'}ErrorActionPreference='SilentlyContinue'
            [Console]::OutputEncoding=New-Object System.Text.UTF8Encoding
            ${'$'}items=New-Object System.Collections.Generic.List[object]
            ${'$'}shell=New-Object -ComObject WScript.Shell
            ${'$'}roots=@([Environment]::GetFolderPath('StartMenu'),[Environment]::GetFolderPath('CommonStartMenu'),[Environment]::GetFolderPath('DesktopDirectory'),[Environment]::GetFolderPath('CommonDesktopDirectory'))
            foreach(${'$'}root in ${'$'}roots){
                Get-ChildItem -LiteralPath ${'$'}root -Filter *.lnk -Recurse | ForEach-Object {
                    ${'$'}target=${'$'}null; ${'$'}link=${'$'}shell.CreateShortcut(${'$'}_.FullName); ${'$'}target=[Environment]::ExpandEnvironmentVariables(${'$'}link.TargetPath)
                    if([IO.Path]::GetFileName(${'$'}target) -ieq 'Update.exe' -and ${'$'}link.Arguments -match '--processStart\s+"?([^"\s]+\.exe)'){
                        ${'$'}app=${'$'}Matches[1]
                        ${'$'}target=Get-ChildItem -LiteralPath (Split-Path ${'$'}target) -Directory -Filter 'app-*' | Sort-Object LastWriteTime -Descending | ForEach-Object { Join-Path ${'$'}_.FullName ${'$'}app } | Where-Object {Test-Path -LiteralPath ${'$'}_ -PathType Leaf} | Select-Object -First 1
                    }
                    if(${'$'}target -and [IO.Path]::GetExtension(${'$'}target) -ieq '.exe' -and (Test-Path -LiteralPath ${'$'}target -PathType Leaf)){
                        ${'$'}items.Add(@{name=${'$'}_.BaseName;path=${'$'}target})
                    }
                }
            }
            foreach(${'$'}key in @('HKCU:\Software\Microsoft\Windows\CurrentVersion\App Paths','HKLM:\Software\Microsoft\Windows\CurrentVersion\App Paths','HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\App Paths')){
                Get-ChildItem ${'$'}key | ForEach-Object {
                    ${'$'}target=${'$'}null; ${'$'}target=[Environment]::ExpandEnvironmentVariables(${'$'}_.GetValue('')).Trim('"')
                    if(${'$'}target -and (Test-Path -LiteralPath ${'$'}target -PathType Leaf)){
                        ${'$'}file=Get-Item -LiteralPath ${'$'}target
                        ${'$'}name=${'$'}file.VersionInfo.ProductName
                        if(-not ${'$'}name){${'$'}name=${'$'}file.BaseName}
                        ${'$'}items.Add(@{name=${'$'}name;path=${'$'}target})
                    }
                }
            }
            ConvertTo-Json -InputObject @(${'$'}items.ToArray()) -Compress
        """.trimIndent()
        val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val output = Files.createTempFile("chspch-app-list-", ".json")
        try {
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Сканирование заняло слишком много времени. Повторите обновление списка.") }
            check(process.exitValue() == 0) { "Не удалось прочитать список установленных приложений" }
            val rows = JSONArray(Files.readString(output).trim().removePrefix("\uFEFF"))
            return unique((0 until rows.length()).mapNotNull { i ->
                runCatching {
                    val r = rows.getJSONObject(i)
                    windowsEntry(r.getString("name"), Path.of(r.getString("path")))
                }.getOrNull()
            })
        } finally { Files.deleteIfExists(output) }
    }
    internal fun windowsEntry(name: String, path: Path): InstalledApp? = runCatching {
        if (!Files.isRegularFile(path) || !path.toString().endsWith(".exe", true) ||
            path.fileName.toString().lowercase() in setOf("chspch.exe", "update.exe", "uninstall.exe", "unins000.exe", "cmd.exe", "powershell.exe", "wscript.exe", "rundll32.exe", "explorer.exe", "msiexec.exe")) null
        else InstalledApp(name, path.toRealPath().toString(), relatedExecutables(path))
    }.getOrNull()
    internal fun relatedExecutables(main: Path): List<String> {
        val canonical = main.toRealPath()
        val folder = canonical.parent
        val generic = setOf("windows", "system32", "syswow64", "program files", "program files (x86)", "desktop", "downloads", "documents", "temp", "bin")
        if (folder.fileName?.toString()?.lowercase() in generic || folder.parent == null) return listOf(canonical.toString())
        val files = mutableListOf(canonical.toString())
        runCatching {
            Files.walk(folder, 3).use { stream -> stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".exe", true) }.limit(300).forEach { file ->
                val name = file.fileName.toString().lowercase()
                if (listOf("unins", "uninstall", "setup", "crash", "update", "installer", "maintenancetool").none { name.startsWith(it) }) {
                    val real = file.toRealPath()
                    if (real.startsWith(folder)) files.add(real.toString())
                }
            } }
        }
        return files.distinct()
    }
    private fun unique(apps: List<InstalledApp>) = apps.distinctBy { if (System.getProperty("os.name").startsWith("Windows")) it.path.lowercase() else it.path }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}
