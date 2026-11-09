package app.chspch.desktop

import java.nio.file.Files
import java.nio.file.Path

object DesktopIntegration {
    private val windows get() = System.getProperty("os.name").startsWith("Windows")
    private fun executable(): Path {
        val resources = System.getProperty("compose.application.resources.dir")?.let(Path::of)
            ?: error("Автозапуск доступен в установленной сборке")
        if (windows) {
            System.getProperty("chspch.app.dir")?.let { return Path.of(it, "Chspch.exe") }
            return resources.parent.parent.resolve("Chspch.exe")
        }
        return resources.parent.parent.parent.resolve("bin/Chspch")
    }
    fun autostart(enabled: Boolean) {
        if (windows) {
            val folder = Path.of(System.getenv("APPDATA"), "Microsoft/Windows/Start Menu/Programs/Startup")
            val file = folder.resolve("Chspch.lnk")
            if (!enabled) { Files.deleteIfExists(file); return }
            val target = executable()
            check(Files.isRegularFile(target)) { "Установленная программа не найдена" }
            Files.createDirectories(folder)
            fun ps(value: String) = "'" + value.replace("'", "''") + "'"
            val script = "\$w=New-Object -ComObject WScript.Shell; \$s=\$w.CreateShortcut(${ps(file.toString())}); \$s.TargetPath=${ps(target.toString())}; \$s.WorkingDirectory=${ps(target.parent.toString())}; \$s.Save()"
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); error("Не удалось настроить автозапуск") }
            check(process.exitValue() == 0) { "Не удалось создать ярлык автозапуска" }
        } else {
            val config = System.getenv("XDG_CONFIG_HOME")?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".config")
            val folder = config.resolve("autostart"); val file = folder.resolve("chspch.desktop")
            if (!enabled) { Files.deleteIfExists(file); return }
            val target = executable(); check(Files.isRegularFile(target)) { "Установленная программа не найдена" }
            Files.createDirectories(folder); Files.writeString(file, desktopEntry(target))
        }
    }
    private fun desktopEntry(executable: Path): String {
        val path = executable.toAbsolutePath().toString().replace("%", "%%").replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$")
        return "[Desktop Entry]\nType=Application\nName=ЧСПЧ\nComment=ВПН трудящихся\nExec=\"$path\"\nIcon=${executable.toAbsolutePath().parent.parent.resolve("icon.png")}\nTerminal=false\nCategories=Network;\nStartupNotify=true\n"
    }
    fun desktopShortcut() {
        check(!windows) { "Используйте Install.exe из Windows-архива" }
        val data = System.getenv("XDG_DATA_HOME")?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".local/share")
        val folder = data.resolve("applications"); Files.createDirectories(folder)
        Files.writeString(folder.resolve("chspch.desktop"), desktopEntry(executable()))
    }
}
