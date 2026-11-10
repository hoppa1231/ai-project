package app.chspch.desktop

import java.net.URI
import kotlin.test.*

class ExternalLinksTest {
    private val link = URI("tg://resolve?domain=test_bot&start=auth_0123456789abcdef0123456789abcdef")

    @Test fun windowsUsesShellWithWholeChallenge() {
        val commands = mutableListOf<List<String>>()
        assertTrue(ExternalLinks.open(link, "Windows 11", { commands += it; true }, { error("No AWT needed") }))
        assertEquals("powershell.exe", commands.single().first())
        assertTrue(commands.single().last().contains("'${link.toASCIIString()}'"))
    }
    @Test fun missingLinuxHandlerFallsBackWithoutLosingQuery() {
        val commands = mutableListOf<List<String>>()
        assertTrue(ExternalLinks.open(link, "Linux", {
            commands += it
            if(it.first()=="xdg-open") error("No xdg-open")
            true
        }, { false }))
        assertEquals(listOf(listOf("xdg-open", link.toASCIIString()), listOf("gio", "open", link.toASCIIString())), commands)
    }
    @Test fun nativeFailureFallsBackToAwt() {
        var received: URI? = null
        assertTrue(ExternalLinks.open(link,"Windows 11", { false }, { received=it; true }))
        assertEquals(link,received)
    }
    @Test fun allFailuresAreReportedForManualFallback() {
        assertFalse(ExternalLinks.open(URI("https://t.me/test_bot?start=auth_0123456789abcdef0123456789abcdef"),"Linux", { false }, { error("No browser") }))
    }
    @Test fun rejectsNonLoginSchemes() {
        assertFailsWith<IllegalArgumentException> { ExternalLinks.open(URI("file:///tmp/test"),"Linux", { error("must not run") }, { false }) }
    }
}
