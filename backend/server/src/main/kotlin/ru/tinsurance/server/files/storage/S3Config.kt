package ru.tinsurance.server.files.storage

import io.ktor.server.config.ApplicationConfig
import ru.tinsurance.server.common.seconds
import ru.tinsurance.server.common.string
import ru.tinsurance.server.common.stringOrNull
import java.net.URI
import java.time.Duration

class S3Config(
    val endpoint: URI,
    val region: String,
    val bucket: String,
    val accessKey: String,
    val secretKey: String,
    val pathStyle: Boolean = true,
    val timeout: Duration = Duration.ofSeconds(30),
    val connectTimeout: Duration = Duration.ofSeconds(5),
) {
    init {
        require(endpoint.scheme in setOf("https", "http") && !endpoint.host.isNullOrBlank()) { "S3_ENDPOINT: нужен HTTP(S) адрес" }
        require(endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null && endpoint.path in setOf("", "/")) {
            "S3_ENDPOINT: укажите адрес сервиса без пути, параметров и учётных данных"
        }
        require(endpoint.scheme == "https" || endpoint.isLoopback()) {
            "S3_ENDPOINT: вне localhost обязателен HTTPS"
        }
        require(region.matches(Regex("[a-z0-9-]+"))) { "S3_REGION: недопустимое значение" }
        require(bucket.matches(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) && ".." !in bucket) { "S3_BUCKET: недопустимое имя" }
        require(accessKey.isNotBlank() && secretKey.isNotBlank()) { "Задайте S3_ACCESS_KEY_ID и S3_SECRET_ACCESS_KEY" }
        require(!timeout.isZero && !timeout.isNegative && timeout <= Duration.ofMinutes(2)) {
            "S3_TIMEOUT_SECONDS должен быть от 1 до 120"
        }
        require(!connectTimeout.isZero && !connectTimeout.isNegative) {
            "S3_CONNECT_TIMEOUT_SECONDS должен быть положительным"
        }
    }

    companion object {
        fun from(config: ApplicationConfig) = S3Config(
            endpoint = URI(required(config, "endpoint", "S3_ENDPOINT")),
            region = config.string("storage.s3.region", "ru-central-1"),
            bucket = required(config, "bucket", "S3_BUCKET"),
            accessKey = required(config, "accessKeyId", "S3_ACCESS_KEY_ID"),
            secretKey = required(config, "secretAccessKey", "S3_SECRET_ACCESS_KEY"),
            pathStyle = config.string("storage.s3.pathStyle", "true").toBooleanStrict(),
            timeout = config.seconds("storage.s3.timeoutSeconds", 30),
            connectTimeout = config.seconds("storage.s3.connectTimeoutSeconds", 5),
        )

        private fun required(config: ApplicationConfig, key: String, env: String): String =
            config.stringOrNull("storage.s3.$key") ?: error("Не задан $env для S3-хранилища")
    }
}

internal fun URI.isLoopback() = host in setOf("localhost", "127.0.0.1", "[::1]")
