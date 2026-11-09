package app.chspch.desktop

import java.nio.file.Files
import kotlin.test.*

class InstalledAppsTest {
    @Test fun `quoted launcher paths and env assignments resolve without executing arguments`() {
        val dir=Files.createTempDirectory("app-scan-test")
        try {
            val file=Files.createFile(dir.resolve("my browser")).toFile().apply{setExecutable(true)}.toPath()
            assertEquals(file.toRealPath().toString(),InstalledApps.executable("env LANG=ru_RU \"$file\" --new-window %U",emptyList()))
            assertEquals(listOf("hello world","%U"),InstalledApps.commandWords("\"hello world\" %U"))
            assertNull(InstalledApps.executable("flatpak run org.example.App",listOf(dir)))
            assertNull(InstalledApps.executable("sh -c 'anything'",listOf(dir)))
            assertEquals(emptyList(),InstalledApps.commandWords("\"unterminated"))
        } finally {Files.walk(dir).use{it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)}}
    }
    @Test fun `plain shell wrapper resolves actual process and shared interpreter is never selected`() {
        val dir=Files.createTempDirectory("app-wrapper-test")
        try {
            val binary=Files.createFile(dir.resolve("browser-bin")).toFile().apply{setExecutable(true)}.toPath()
            val wrapper=dir.resolve("browser")
            Files.writeString(wrapper,"#!/bin/sh\nexec \"$binary\" \"${'$'}@\"\n");wrapper.toFile().setExecutable(true)
            assertEquals(binary.toRealPath().toString(),InstalledApps.executable("browser %u",listOf(dir)))
            Files.writeString(wrapper,"#!/bin/sh\nexec \"${'$'}RUNTIME\" app.js\n")
            assertNull(InstalledApps.executable("browser",listOf(dir)))
        } finally {Files.walk(dir).use{it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)}}
    }
    @Test fun `application exe group includes helpers but not uninstallers or neighboring programs`() {
        val root=Files.createTempDirectory("exe-group")
        try {
            val folder=Files.createDirectories(root.resolve("Browser"))
            val main=Files.createFile(folder.resolve("browser.exe"))
            val helper=Files.createFile(Files.createDirectories(folder.resolve("helpers")).resolve("network.exe"))
            Files.createFile(folder.resolve("uninstall.exe"));Files.createFile(root.resolve("other.exe"))
            assertEquals(setOf(main.toRealPath().toString(),helper.toRealPath().toString()),InstalledApps.relatedExecutables(main).toSet())
        } finally {Files.walk(root).use{it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)}}
    }
    @Test fun `unreadable or stale Windows entries cannot abort the entire app list`() {
        val root=Files.createTempDirectory("windows-app-entry")
        try {
            val app=Files.createFile(root.resolve("app.exe"))
            assertNull(InstalledApps.windowsEntry("Missing",root.resolve("missing.exe")))
            assertNull(InstalledApps.windowsEntry("Uninstaller",Files.createFile(root.resolve("uninstall.exe"))))
            val rows=listOf(root.resolve("missing.exe"),app).mapNotNull{InstalledApps.windowsEntry("Test",it)}
            assertEquals(1,rows.size);assertEquals(app.toRealPath().toString(),rows.single().path)
        } finally {Files.walk(root).use{it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)}}
    }
    @Test fun `user menu overrides system menu and hidden entries suppress lower priority entries`() {
        val root=Files.createTempDirectory("desktop-app-list")
        try {
            val user=Files.createDirectories(root.resolve("user"));val system=Files.createDirectories(root.resolve("system"));val bin=Files.createDirectories(root.resolve("bin"))
            val executable=Files.createFile(bin.resolve("browser")).toFile().apply{setExecutable(true)}.toPath()
            fun entry(name:String,extra:String="")="[Desktop Entry]\nType=Application\nName=$name\nName[ru]=Браузер\nExec=browser %U\n$extra"
            Files.writeString(system.resolve("browser.desktop"),entry("Browser"))
            Files.writeString(user.resolve("browser.desktop"),"[Desktop Entry]\nHidden=true\n")
            assertTrue(InstalledApps.linux(listOf(user,system),listOf(bin),"ru").isEmpty())
            Files.delete(user.resolve("browser.desktop"))
            Files.writeString(system.resolve("duplicate.desktop"),entry("Duplicate"))
            Files.writeString(system.resolve("hidden.desktop"),entry("Hidden","NoDisplay=true\n"))
            Files.writeString(system.resolve("action.desktop"),"[Desktop Entry]\nType=Application\nName=No executable\n[Desktop Action Open]\nExec=browser\n")
            val apps=InstalledApps.linux(listOf(user,system),listOf(bin),"ru")
            assertEquals(1,apps.size);assertEquals("Браузер",apps.single().name)
            assertEquals(executable.toRealPath().toString(),apps.single().path)
        } finally {Files.walk(root).use{it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)}}
    }
}
