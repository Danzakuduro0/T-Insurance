package com.tinsurance.app.data

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.authProvider
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import ru.tinsurance.api.*
import ru.tinsurance.api.mobile.ClaimDetails
import ru.tinsurance.api.mobile.ClaimListItem
import ru.tinsurance.api.mobile.CompletenessDto
import ru.tinsurance.api.mobile.CreateClaimRequest
import ru.tinsurance.api.mobile.EventTypeDto
import ru.tinsurance.api.mobile.NotificationDto
import ru.tinsurance.api.mobile.NotificationsPage
import ru.tinsurance.api.mobile.TimelineEvent
import ru.tinsurance.api.mobile.UpdateClaimRequest

/** Сообщение для клиента уже на русском: из ErrorResponse сервера или общее. */
class ApiException(val status: Int, message: String) : Exception(message) {
    val unauthorized get() = status == HttpStatusCode.Unauthorized.value
}

/**
 * Клиент `/api/mobile`. Access-токен (15 мин) живёт в памяти и обновляется по refresh-токену
 * автоматически; refresh-токен сохраняется в [SessionStorage], поэтому после перезапуска
 * приложения вход восстанавливается через [restore].
 */
@OptIn(ExperimentalUuidApi::class)
class MobileApiClient(
    baseUrl: String = defaultApiBaseUrl,
    private val storage: SessionStorage = SessionStorage(),
    engine: (HttpClientConfig<*>.() -> Unit) -> HttpClient = { HttpClient(it) },
) {
    private var tokens: BearerTokens? = null

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
                    try {
                        val response =
                            client
                                .post(path(MobileApi.REFRESH)) {
                                    markAsRefreshTokenRequest()
                                    contentType(ContentType.Application.Json)
                                    setBody(RefreshRequest(refreshToken))
                                }
                                .body<AuthResponse>()
                        remember(response)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: ResponseException) {
                        // Сервер отверг токен (истёк, отозван, уже использован): сессии больше нет.
                        forget()
                        null
                    } catch (_: Throwable) {
                        // Нет связи: сохранённый токен не трогаем, он ещё может сработать позже.
                        null
                    }
                }
                sendWithoutRequest { request ->
                    !request.url.pathSegments.joinToString("/").endsWith(path(MobileApi.LOGIN))
                }
            }
        }
    }

    val signedIn get() = tokens != null

    /** Есть сохранённый вход с прошлого запуска — его можно восстановить через [restore]. */
    val hasStoredSession get() = storage.refreshToken != null

    /**
     * Восстанавливает вход по сохранённому refresh-токену. null — сохранённого входа нет
     * или сервер его больше не принимает; ApiException — нет связи (токен остаётся для новой попытки).
     */
    suspend fun restore(): UserDto? {
        val refreshToken = storage.refreshToken ?: return null
        return try {
            val response =
                http
                    .post(path(MobileApi.REFRESH)) {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshRequest(refreshToken))
                    }
                    .body<AuthResponse>()
            remember(response)
            http.authProvider<BearerAuthProvider>()?.clearToken()
            response.user
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ResponseException) {
            forget()
            null
        } catch (_: Throwable) {
            throw ApiException(0, "Нет связи с сервером. Проверьте подключение и попробуйте снова.")
        }
    }

    // Refresh-токен одноразовый: каждый новый сразу сохраняем, иначе следующий запуск не войдёт.
    private fun remember(response: AuthResponse): BearerTokens {
        storage.refreshToken = response.refreshToken
        return BearerTokens(response.accessToken, response.refreshToken).also { tokens = it }
    }

    private fun forget() {
        tokens = null
        storage.refreshToken = null
    }

    suspend fun login(phone: String, password: String): UserDto = call {
        val response =
            http
                .post(path(MobileApi.LOGIN)) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        MobileLoginRequest(
                            phone = phone,
                            password = password,
                            device = DeviceInfo(storage.installationId, devicePlatform),
                        )
                    )
                }
                .body<AuthResponse>()
        remember(response)
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
            forget()
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

    /** Типы событий с правилами формы: какие поля и документы нужны. */
    suspend fun eventTypes(): List<EventTypeDto> = call {
        http.get(path(MobileApi.EVENT_TYPES)).body()
    }

    /** Черновик; повтор с тем же clientRequestId вернёт уже созданный. */
    suspend fun createClaim(request: CreateClaimRequest): ClaimDetails = call {
        http
            .post(path(MobileApi.CLAIMS)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            .body()
    }

    /** Только в черновике: лишний файл помечается удалённым. */
    suspend fun deleteFile(claimId: Long, fileId: Long) {
        call {
            http.delete(
                claimPath(MobileApi.CLAIM_FILE, claimId).replace("{fileId}", fileId.toString())
            )
        }
    }

    /** В черновике меняются любые поля; после запроса уточнений — только запрошенные. null — не меняется. */
    suspend fun updateClaim(id: Long, request: UpdateClaimRequest): ClaimDetails = call {
        http
            .patch(claimPath(MobileApi.CLAIM, id)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            .body()
    }

    /** Чего не хватает для отправки — тот же расчёт, что сервер делает при submit. */
    suspend fun completeness(id: Long): CompletenessDto = call {
        http.get(claimPath(MobileApi.CLAIM_COMPLETENESS, id)).body()
    }

    /**
     * Отправка или повторная отправка после дозагрузки. Без части документов сервер принимает обращение
     * и сразу запрашивает недостающее (NEEDS_INFO); 400 — не заполнены основные поля, 409 — пункты
     * запроса не выполнены.
     */
    suspend fun submit(id: Long): ClaimDetails = call {
        http.post(claimPath(MobileApi.CLAIM_SUBMIT, id)).body()
    }

    /**
     * Загрузка файла. Отклонённый файл (422) — не ошибка: приходит ClaimFileDto со status = REJECTED,
     * причиной и подсказкой. 413 и прочие ошибки — ApiException.
     */
    suspend fun uploadFile(
        claimId: Long,
        documentType: String,
        fileName: String,
        bytes: ByteArray,
        replacesFileId: Long? = null,
        clientRequestId: String = Uuid.random().toString(),
    ): ClaimFileDto = call {
        val response =
            http.submitFormWithBinaryData(
                claimPath(MobileApi.CLAIM_FILES, claimId),
                formData {
                    append("documentType", documentType)
                    append("clientRequestId", clientRequestId)
                    replacesFileId?.let { append("replacesFileId", it.toString()) }
                    append(
                        "file",
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, mimeType(fileName))
                            append(HttpHeaders.ContentDisposition, "filename=\"${fileName.asciiName()}\"")
                        },
                    )
                },
            ) {
                expectSuccess = false
            }
        when (response.status) {
            HttpStatusCode.OK,
            HttpStatusCode.Created,
            HttpStatusCode.UnprocessableEntity -> response.body<ClaimFileDto>()
            else -> throw ClientRequestException(response, "")
        }
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
        } catch (e: Throwable) {
            // Throwable, а не Exception: в браузере сбой сети приходит как kotlin.Error("Fail to fetch")
            // с JsError внутри — Exception его не ловит, и ошибка роняла корутину экрана.
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

/** Тип по расширению; сервер всё равно проверяет содержимое файла. */
fun mimeType(fileName: String) =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg",
        "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

// Заголовок Content-Disposition — только ASCII; имя файла сервер берёт отсюда для отображения.
private fun String.asciiName() =
    map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")

/** Телефон в формате сервера: +7XXXXXXXXXX. Ввод вида 8 900…, 900…, +7 (900)… */
fun normalizePhone(input: String): String {
    val digits = input.filter { it.isDigit() }
    return when {
        digits.length == 10 -> "+7$digits"
        digits.length == 11 && (digits[0] == '8' || digits[0] == '7') -> "+7${digits.drop(1)}"
        else -> if (input.trim().startsWith("+")) "+$digits" else digits
    }
}
