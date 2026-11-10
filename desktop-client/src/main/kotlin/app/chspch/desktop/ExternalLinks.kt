package app.chspch.desktop

import java.awt.Desktop
import java.net.URI
import java.util.concurrent.TimeUnit

/** A successful dispatch does not prove a browser or protocol handler became visible. */
internal object ExternalLinks {
    fun open(
        uri: URI,
        os: String = System.getProperty("os.name"),
        runCommand: (List<String>) -> Boolean = ::launch,
        browse: (URI) -> Boolean = { target ->
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(target)
                true
            } else false
        }
    ): Boolean {
        require(uri.scheme in listOf("https", "tg")) { "Неподдерживаемая ссылка" }
        val url = uri.toASCIIString()
        val commands = when {
            os.startsWith("Windows") -> listOf(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "Start-Process -FilePath '" + url.replace("'", "''") + "' -ErrorAction Stop"))
            os.startsWith("Mac") -> listOf(listOf("open", url))
            else -> listOf(listOf("xdg-open", url), listOf("gio", "open", url))
        }
        for (command in commands) {
            if (runCatching { runCommand(command) }.getOrDefault(false)) return true
        }
        return runCatching { browse(uri) }.getOrDefault(false)
    }

    private fun launch(command: List<String>): Boolean {
        val process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return false
        }
        return process.exitValue() == 0
    }
}
