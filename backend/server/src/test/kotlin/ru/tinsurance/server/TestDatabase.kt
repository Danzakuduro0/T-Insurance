package ru.tinsurance.server

import io.ktor.server.config.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection

/** Настоящий PostgreSQL 16, поднимается один раз на весь прогон тестов (Docker не нужен). */
object TestDatabase {
    private val postgres: EmbeddedPostgres by lazy { EmbeddedPostgres.start() }

    /** Каталог файлов обращений на время прогона тестов. */
    val storageDir: java.nio.file.Path by lazy { java.nio.file.Files.createTempDirectory("tstrah-files-") }

    private fun base() = mapOf(
        "db.url" to postgres.getJdbcUrl("postgres", "postgres"),
        "db.user" to "postgres",
        "db.password" to "postgres",
        "db.maxPoolSize" to "2",
        "jwt.secret" to "test-secret-test-secret-test-secret-123",
        "jwt.issuer" to "tstrah-test",
        "jwt.accessTokenTtlMinutes" to "15",
        "jwt.mobileRefreshTtlDays" to "60",
        "jwt.webRefreshTtlHours" to "12",
        "demo.seed" to "true",
        "app.mode" to "dev",
        "storage.localDir" to storageDir.toString(),
        // фоновый воркер в тестах не крутится сам — тест вызывает runReady() там, где нужно
        "jobs.enabled" to "false",
        // автоназначение включают только тесты назначения — остальные ждут обращения в очереди
        "assignment.autoAssign" to "false",
    )

    /** Конфиг тестов; overrides заменяют значения, without — убирает ключи. */
    fun config(vararg overrides: Pair<String, String>, without: Set<String> = emptySet()) =
        MapApplicationConfig(*(base() + overrides).filterKeys { it !in without }.toList().toTypedArray())

    fun connection(): Connection = postgres.postgresDatabase.connection
}
