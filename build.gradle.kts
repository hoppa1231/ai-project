val kotlin_version: String by project
val logback_version: String by project
val ktor_version = "3.4.0"  // Используем более новую версию плагина

plugins {
    kotlin("jvm") version "2.3.0"
    id("io.ktor.plugin") version "3.4.0"  // Более новая версия из первого файла
}

group = "com.example"
version = "0.0.1"

application {
    mainClass.set("io.ktor.server.netty.EngineMain")  // Из первого файла
    // Альтернатива на случай проблем:
    // mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // AsyncAPI (из первого файла)
    implementation("org.openfolder:kotlin-asyncapi-ktor:3.1.3")

    // Ktor Core (из обоих файлов)
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-config-yaml")

    // OpenAPI и Swagger (объединение из обоих файлов)
    implementation("io.ktor:ktor-server-openapi")
    implementation("io.ktor:ktor-server-routing-openapi")  // Из первого файла
    implementation("io.ktor:ktor-server-swagger")

    // Content Negotiation и JSON (из второго файла)
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")

    // Версии с указанием (из первого файла для client, из второго для остального)
    implementation("io.ktor:ktor-client-core:${ktor_version}")
    implementation("io.ktor:ktor-client-cio:${ktor_version}")

    // Логирование (из обоих файлов)
    implementation("ch.qos.logback:logback-classic:$logback_version")

    // Exposed ORM (из второго файла)
    implementation("org.jetbrains.exposed:exposed-core:0.45.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.45.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.45.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.45.0")

    // База данных (из второго файла)
    implementation("org.postgresql:postgresql:42.7.1")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Тестирование (из обоих файлов)
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
}