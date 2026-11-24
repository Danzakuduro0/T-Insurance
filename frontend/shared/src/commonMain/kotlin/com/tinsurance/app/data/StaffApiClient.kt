package com.tinsurance.app.data

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import ru.tinsurance.api.AuthResponse
import ru.tinsurance.api.ErrorResponse
import ru.tinsurance.api.OperatorApi
import ru.tinsurance.api.OperatorLoginRequest
import ru.tinsurance.api.RefreshRequest
import ru.tinsurance.api.Role
import ru.tinsurance.api.UserDto

internal class StaffApiClient(
    private val store: KeyValueStore,
    baseUrl: String = defaultApiBaseUrl,
    engine: (HttpClientConfig<*>.() -> Unit) -> HttpClient = { HttpClient(it) },
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private var tokens: BearerTokens? = null

    private val http: HttpClient = engine {
        expectSuccess = true
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 10_000 }
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(Auth) {
            reAuthorizeOnResponse { response ->
                response.status == HttpStatusCode.Unauthorized &&
                    response.call.request.url.encodedPath == OperatorApi.LOGOUT
            }
            bearer {
                cacheTokens = false
                loadTokens { tokens }
                sendWithoutRequest { it.url.encodedPath == OperatorApi.LOGOUT }
                refreshTokens {
                    val token = oldTokens?.refreshToken ?: return@refreshTokens null
                    refresh(client, token) { markAsRefreshTokenRequest() }
                    tokens
                }
            }
        }
    }

    val hasStoredSession get() = store.get(RefreshTokenKey) != null

    suspend fun login(email: String, password: String): UserDto = request("Неверный email или пароль.") {
        val session = http.post(baseUrl + OperatorApi.LOGIN) {
            contentType(ContentType.Application.Json)
            setBody(OperatorLoginRequest(email.trim(), password))
        }.body<AuthResponse>()
        remember(session)
    }

    suspend fun restore(): UserDto? = store.get(RefreshTokenKey)?.let { refresh(http, it) }

    suspend fun logout() {
        try {
            if (tokens != null) request { http.post(baseUrl + OperatorApi.LOGOUT) }
        } finally {
            forget()
        }
    }

    fun close() = http.close()

    private suspend fun refresh(
        client: HttpClient,
        refreshToken: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): UserDto = try {
        request {
            val session = client.post(baseUrl + OperatorApi.REFRESH) {
                configure()
                contentType(ContentType.Application.Json)
                setBody(RefreshRequest(refreshToken))
            }.body<AuthResponse>()
            remember(session)
        }
    } catch (error: ApiException) {
        if (error.unauthorized || error.status == HttpStatusCode.Forbidden.value) forget()
        throw error
    }

    private fun remember(session: AuthResponse): UserDto {
        if (session.user.role != Role.OPERATOR && session.user.role != Role.ADMIN) {
            forget()
            throw ApiException(HttpStatusCode.Forbidden.value, "Вход доступен только сотрудникам.")
        }
        store.set(RefreshTokenKey, session.refreshToken)
        tokens = BearerTokens(session.accessToken, session.refreshToken)
        return session.user
    }

    private fun forget() {
        tokens = null
        store.set(RefreshTokenKey, null)
    }

    private suspend fun <T> request(
        unauthorizedMessage: String = "Сессия истекла. Войдите снова.",
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: ApiException) {
        throw error
    } catch (error: ResponseException) {
        val status = error.response.status
        val message = try {
            error.response.body<ErrorResponse>().message
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        val fallback = when (status) {
            HttpStatusCode.Unauthorized -> unauthorizedMessage
            HttpStatusCode.TooManyRequests -> "Слишком много попыток. Попробуйте позже."
            else -> "Сервер не смог выполнить запрос. Попробуйте позже."
        }
        throw ApiException(status.value, message ?: fallback)
    } catch (_: Throwable) {
        throw ApiException(0, "Нет связи с сервером. Проверьте подключение и попробуйте снова.")
    }

    private companion object {
        const val RefreshTokenKey = "tinsurance.staff.refreshToken"
    }
}
