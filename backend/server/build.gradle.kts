plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
}

group = "ru.tinsurance"
version = "1.0.0"
application {
    mainClass = "ru.tinsurance.server.ApplicationKt"
}

dependencies {
    api(project(":core"))
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverConfigYaml)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serverCallLogging)
    implementation(libs.ktor.serializationKotlinxJson)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverAuthJwt)
    implementation(libs.ktor.serverCors)
    implementation(libs.ktor.serverRateLimit)
    implementation(libs.ktor.serverBodyLimit)
    implementation(libs.bcrypt)

    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.javaTime)
    implementation(libs.exposed.json)

    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.clientContentNegotiation)
    testImplementation(libs.kotlin.testJunit)
    // Настоящий PostgreSQL в тестах без Docker
    testImplementation(libs.embeddedPostgres)
    testImplementation(platform(libs.embeddedPostgres.binariesBom))
}

// ./gradlew :server:run — локальная разработка: режим dev (случайный JWT-секрет, демо-данные).
// Собранный jar/образ этого флага не получает и работает как prod.
tasks.named<JavaExec>("run") {
    systemProperty("io.ktor.development", "true")
}
