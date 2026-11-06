package com.tinsurance.app.data

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.authProvider
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import ru.tinsurance.api.*
import ru.tinsurance.api.mobile.ClaimDetails
import ru.tinsurance.api.mobile.ClaimListItem
import ru.tinsurance.api.mobile.NotificationDto
import ru.tinsurance.api.mobile.NotificationsPage
import ru.tinsurance.api.mobile.TimelineEvent

/** Сообщение для клиента уже на русском: из ErrorResponse сервера или общее. */
class ApiException(val status: Int, message: String) : Exception(message) {
    val unauthorized get() = status == HttpStatusCode.Unauthorized.value
}

/**
 * Клиент `/api/mobile`. Токены хранятся в памяти: после перезапуска нужно войти снова.
 * Просроченный access-токен (15 мин) обновляется по refresh-токену автоматически.
 */
@OptIn(ExperimentalUuidApi::class)
class MobileApiClient(
    baseUrl: String = defaultApiBaseUrl,
    engine: (HttpClientConfig<*>.() -> Unit) -> HttpClient = { HttpClient(it) },
) {
    private var tokens: BearerTokens? = null
    private val installationId = Uuid.random().toString()

    private val json = Json { ignoreUnknownKeys = true }

    private val http = engine {
        expectSuccess = true
        defaultRequest { url(baseUrl.trimEnd('/') + "/") }
        install(ContentNegotiation) { json(json) }
        install(Auth) {
            bearer {
                loadTokens { tokens }
                refreshTokens {
                    val refreshToken = oldTokens?.refreshToken ?: return@refreshTokens null
                    val response =
                        runCatching {
                                client
                                    .post(path(MobileApi.REFRESH)) {
                                        markAsRefreshTokenRequest()
                                        contentType(ContentType.Application.Json)
                                        setBody(RefreshRequest(refreshToken))
                                    }
                                    .body<AuthResponse>()
                            }
                            .getOrNull()
                    tokens = response?.let { BearerTokens(it.accessToken, it.refreshToken) }
                    tokens
                }
                sendWithoutRequest { request ->
                    !request.url.pathSegments.joinToString("/").endsWith(path(MobileApi.LOGIN))
                }
            }
        }
    }

    val signedIn get() = tokens != null

    suspend fun login(phone: String, password: String): UserDto = call {
        val response =
            http
                .post(path(MobileApi.LOGIN)) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        MobileLoginRequest(
                            phone = phone,
                            password = password,
                            device = DeviceInfo(installationId, devicePlatform),
                        )
                    )
                }
                .body<AuthResponse>()
        tokens = BearerTokens(response.accessToken, response.refreshToken)
        // Иначе Auth продолжит отдавать закешированные токены прошлой сессии.
        http.authProvider<BearerAuthProvider>()?.clearToken()
        response.user
    }

    suspend fun logout() {
        if (tokens == null) return
        try {
            call { http.post(path(MobileApi.LOGOUT)) }
        } catch (_: ApiException) {
            // Сессия на сервере истечёт сама; локально выходим в любом случае.
        } finally {
            tokens = null
            http.authProvider<BearerAuthProvider>()?.clearToken()
        }
    }

    suspend fun me(): UserDto = call { http.get(path(MobileApi.ME)).body() }

    suspend fun policies(): List<PolicyDto> = call { http.get(path(MobileApi.POLICIES)).body() }

    suspend fun claims(): List<ClaimListItem> = call { http.get(path(MobileApi.CLAIMS)).body() }

    suspend fun claim(id: Long): ClaimDetails = call { http.get(claimPath(MobileApi.CLAIM, id)).body() }

    suspend fun timeline(id: Long): List<TimelineEvent> = call {
        http.get(claimPath(MobileApi.CLAIM_TIMELINE, id)).body()
    }

    suspend fun submit(id: Long): ClaimDetails = call {
        http.post(claimPath(MobileApi.CLAIM_SUBMIT, id)).body()
    }

    suspend fun uploadFile(
        id: Long,
        documentType: String,
        name: String,
        bytes: ByteArray,
        replacesFileId: Long? = null,
        clientRequestId: String = Uuid.random().toString(),
    ): ClaimFileDto = call {
        val fileName = name.replace('\\', '_').replace('"', '_').replace('\r', '_').replace('\n', '_')
        val form = MultiPartFormDataContent(formData {
            append("documentType", documentType)
            append("clientRequestId", clientRequestId)
            replacesFileId?.let { append("replacesFileId", it.toString()) }
            append("file", bytes, headersOf(
                HttpHeaders.ContentType to listOf(mimeType(name)),
                HttpHeaders.ContentDisposition to listOf("filename=\"$fileName\""),
            ))
        })
        try {
            http.post(claimPath(MobileApi.CLAIM_FILES, id)) { setBody(form) }.body()
        } catch (e: ClientRequestException) {
            if (e.response.status != HttpStatusCode.UnprocessableEntity) throw e
            // 422 с ClaimFileDto — результат проверки файла, а не ошибка соединения.
            val body = e.response.bodyAsText()
            val file = runCatching { json.decodeFromString<ClaimFileDto>(body) }.getOrNull()
            file?.takeIf { it.status == FileStatus.REJECTED } ?: throw e
        }
    }

    /** Страница уведомлений, новые сверху; следующая — с beforeId = nextBeforeId. */
    suspend fun notifications(
        beforeId: Long? = null,
        limit: Int = 20,
        unreadOnly: Boolean = false,
    ): NotificationsPage = call {
        http
            .get(path(MobileApi.NOTIFICATIONS)) {
                parameter("limit", limit)
                parameter("beforeId", beforeId)
                if (unreadOnly) parameter("unreadOnly", true)
            }
            .body()
    }

    suspend fun markNotificationRead(id: Long): NotificationDto = call {
        http.post(claimPath(MobileApi.NOTIFICATION_READ, id)).body()
    }

    private fun claimPath(apiPath: String, id: Long) = path(apiPath.replace("{id}", id.toString()))

    private suspend fun <T> call(block: suspend () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: ApiException) {
            throw e
        } catch (e: ResponseException) {
            val status = e.response.status
            val error = runCatching { e.response.body<ErrorResponse>() }.getOrNull()
            if (status == HttpStatusCode.Unauthorized) tokens = null
            throw ApiException(status.value, error?.message ?: defaultMessage(status))
        } catch (e: Exception) {
            throw ApiException(0, "Нет связи с сервером. Проверьте подключение и попробуйте снова.")
        }

    private fun defaultMessage(status: HttpStatusCode) =
        when (status) {
            HttpStatusCode.Unauthorized -> "Сессия истекла. Войдите снова."
            HttpStatusCode.TooManyRequests -> "Слишком много попыток. Попробуйте позже."
            else -> "Сервер не смог выполнить запрос (${status.value}). Попробуйте позже."
        }

    // Пути в ApiPaths начинаются с "/": убираем, чтобы они складывались с baseUrl.
    private fun path(apiPath: String) = apiPath.removePrefix("/")
}

/** Телефон в формате сервера: +7XXXXXXXXXX. Ввод вида 8 900…, 900…, +7 (900)… */
fun normalizePhone(input: String): String {
    val digits = input.filter { it.isDigit() }
    return when {
        digits.length == 10 -> "+7$digits"
        digits.length == 11 && (digits[0] == '8' || digits[0] == '7') -> "+7${digits.drop(1)}"
        else -> if (input.trim().startsWith("+")) "+$digits" else digits
    }
}

fun mimeType(name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }
