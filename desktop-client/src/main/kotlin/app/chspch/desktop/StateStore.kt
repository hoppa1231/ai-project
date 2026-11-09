package app.chspch.desktop

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** App-owned files only; never put sessions/configs in the checkout or logs. */
class StateStore(val directory: Path = defaultDirectory()) {
    private val file = directory.resolve("session.json")
    val data: JSONObject
    init {
        Files.createDirectories(directory)
        restrict(directory, true)
        data = if (Files.exists(file)) JSONObject(Files.readString(file)) else JSONObject()
        if (!data.has("fingerprint")) {
            data.put("fingerprint", "desktop-${UUID.randomUUID()}")
            save()
        }
    }
    @Synchronized fun save() {
        val temporary = Files.createTempFile(directory, "session-", ".tmp")
        try {
            restrict(temporary)
            Files.writeString(temporary, data.toString())
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }
    companion object {
        fun defaultDirectory(): Path {
            val windows = System.getProperty("os.name").startsWith("Windows", true)
            val base = if (windows) System.getenv("LOCALAPPDATA")?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), "AppData", "Local")
            else System.getenv("XDG_CONFIG_HOME")?.takeIf { it.startsWith("/") }?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".config")
            return base.resolve("chspch")
        }
        fun restrict(path: Path, directory: Boolean = false) {
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
            }
        }
    }
}
