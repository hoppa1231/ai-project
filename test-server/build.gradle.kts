plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
}

group = "com.example"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic) // Логирование
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.config.yaml)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
    // Зависимости для Exposed'а
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc) 
    implementation(libs.exposed.dao)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)
    implementation(libs.exposed.crypt)
    implementation(libs.exposed.migration.jdbc)
    // БД
    implementation(libs.postgresql)
    implementation(libs.hikaricp)

    implementation(libs.h2)
}
