package ru.tinsurance.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import io.ktor.server.config.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import ru.tinsurance.server.common.int

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int,
) {
    companion object {
        fun from(config: ApplicationConfig) = DatabaseConfig(
            url = config.property("db.url").getString(),
            user = config.property("db.user").getString(),
            password = config.property("db.password").getString(),
            maxPoolSize = config.int("db.maxPoolSize", 10),
        )
    }
}

/** Пул соединений → миграции Flyway (resources/db/migration) → подключение Exposed. */
fun Application.configureDatabase(config: DatabaseConfig): Database {
    val dataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user
            password = config.password
            maximumPoolSize = config.maxPoolSize
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            poolName = "tstrah-db"
        }
    )

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate()

    monitor.subscribe(ApplicationStopped) { dataSource.close() }

    return Database.connect(dataSource)
}
