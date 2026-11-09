package app.chspch.desktop

import java.net.URI
import java.nio.file.Files
import kotlin.test.*

class DesktopUpdatesPublicTest {
    @Test fun verifiedDownloadFromPublicRelease() {
        if(System.getenv("CHSPCH_UPDATE_PUBLIC_SMOKE")!="1")return
        val text=URI(DesktopUpdates.FEED).toURL().openConnection().apply{connectTimeout=10000;readTimeout=20000}.getInputStream().use{it.readNBytes(65536).toString(Charsets.UTF_8)}
        val release=DesktopUpdates.parse(text)
        assertTrue(DesktopUpdates.newer(release.version,"1.0.3"))
        assertNull(DesktopUpdates.check()) // Published version equals the running build.
        val dir=Files.createTempDirectory("chspch-update-download-test")
        try {
            val file=DesktopUpdates.download(release,dir){}
            assertEquals(release.bytes,Files.size(file))
            println("Public release ${release.version}: verified download and SHA256; current version does not offer itself")
        } finally {Files.walk(dir).use{it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)}}
    }
}
