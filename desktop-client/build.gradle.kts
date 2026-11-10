import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.compose") version "1.6.11"
}

kotlin { jvmToolchain(21) }
dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}
tasks.test { useJUnit() }
compose.desktop {
    application {
        mainClass = "app.chspch.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            appResourcesRootDir.set(project.layout.projectDirectory.dir("packaging"))
            packageName = "Chspch"
            packageVersion = "1.0.7"
            description = "ЧСПЧ — выборочная маршрутизация"
            vendor = "Chspch"
            modules("java.net.http", "jdk.crypto.ec", "java.prefs", "jdk.unsupported")
            windows { iconFile.set(project.file("packaging/windows-x64/chspch.ico")); menuGroup = "ЧСПЧ"; shortcut = true; upgradeUuid = "95a05f86-1036-4bc3-b624-9a2203bcc574" }
            linux { shortcut = true }
        }
    }
}

tasks.register<JavaExec>("smoke") {
    group = "verification"
    description = "Opt-in live guest/proxy smoke; creates and revokes an isolated test config"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("app.chspch.desktop.SmokeKt")
}

// Compose copies resource files without executable bits. Fix the bundled Linux core
// in the application image, including images later consumed by DEB/RPM packaging.
tasks.matching { it.name == "createDistributable" }.configureEach {
    doLast {
        if (System.getProperty("os.name").startsWith("Linux")) {
            fileTree(layout.buildDirectory.dir("compose/binaries/main/app")) {
                include("**/resources/core/sing-box", "**/resources/helper/chspch-tun-helper")
            }.forEach { check(it.setExecutable(true, false)) { "Cannot mark bundled core executable" } }
        }
    }
}

val buildTunHelper by tasks.registering(Exec::class) {
    val platform = if (System.getProperty("os.name").startsWith("Windows")) "windows-x64" else "linux-x64"
    commandLine(if (platform.startsWith("windows")) "python" else "python3", "scripts/build-helper.py", platform)
    inputs.dir("helper")
    inputs.file("scripts/build-helper.py")
    inputs.file("packaging/$platform/core/sing-box" + if (platform.startsWith("windows")) ".exe" else "")
    outputs.dir("packaging/$platform/helper")
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(buildTunHelper) }

val windowsNativeRuntime by configurations.creating { isTransitive = false }
 dependencies { add(windowsNativeRuntime.name, "org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.8.4") }
tasks.register<Sync>("prepareWindowsJars") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(tasks.jar)
    from(configurations.runtimeClasspath) { exclude("skiko-awt-runtime-linux-*", "skiko-awt-runtime-macos-*") }
    from(windowsNativeRuntime)
    from(tasks.jar)
    into(layout.buildDirectory.dir("windows-jars"))
}

tasks.register<JavaExec>("accountSmoke") {
    group = "verification"
    description = "Explicit opt-in: tests system TUN with the existing linked desktop account"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("app.chspch.desktop.AccountSmokeKt")
    jvmArgs("-Dhttp.keepAlive=false")
}

tasks.register<JavaExec>("websiteSmoke") {
    group = "verification"
    description = "Opt-in live verification of myanimelist.net with the linked desktop account"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("app.chspch.desktop.WebsiteSmokeKt")
    jvmArgs("-Dhttp.keepAlive=false")
}
