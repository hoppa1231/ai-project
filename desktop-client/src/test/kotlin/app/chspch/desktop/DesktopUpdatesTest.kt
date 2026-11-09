package app.chspch.desktop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.test.*

class DesktopUpdatesTest {
    private val bytes="verified archive".toByteArray()
    private val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun manifest(url:String="https://tech-supp-test.ru/updates/desktop/releases/chspch-windows-x64-1.10.0-aaaaaaaaaaaa.zip")="""{"schemaVersion":1,"product":"ChspchDesktop","versionName":"1.10.0","notes":"Новая версия","artifacts":{"windows-x64":{"url":"$url","sizeBytes":16,"sha256":"${"a".repeat(64)}"}}}"""
    @Test fun `versions compare numerically and never offer downgrade`() {
        assertTrue(DesktopUpdates.newer("1.10.0","1.9.9"));assertFalse(DesktopUpdates.newer("1.0.0","1.0.0"));assertFalse(DesktopUpdates.newer("1.0.0","2.0.0"))
        assertFails{DesktopUpdates.newer("1.0.0-beta")}
    }
    @Test fun `feed only permits product and exact HTTPS release path for platform`() {
        assertEquals("1.10.0",DesktopUpdates.parse(manifest(),"windows-x64").version)
        for(url in listOf("http://tech-supp-test.ru/updates/desktop/releases/test.zip","https://example.com/updates/desktop/releases/test.zip","https://tech-supp-test.ru/updates/desktop/releases/../test.zip","https://tech-supp-test.ru/updates/desktop/releases/chspch-windows-x64-1.10.0-aaaaaaaaaaaa.zip?token=bad"))assertFails{DesktopUpdates.parse(manifest(url),"windows-x64")}
        assertFails{DesktopUpdates.parse(manifest().replace("ChspchDesktop","other"),"windows-x64")}
    }
    @Test fun `downloads reject truncated oversized or corrupted payloads`() {
        fun copy(data:ByteArray,expected:Long=bytes.size.toLong(),sha:String=hash):ByteArray{
            val out=ByteArrayOutputStream();DesktopUpdates.copyVerified(ByteArrayInputStream(data),out,expected,sha);return out.toByteArray()
        }
        assertContentEquals(bytes,copy(bytes))
        assertFails{copy(bytes.dropLast(1).toByteArray())};assertFails{copy(bytes+byteArrayOf(0))};assertFails{copy(bytes,sha="0".repeat(64))}
    }
}
