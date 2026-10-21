package ru.tinsurance.api

import kotlinx.serialization.Serializable

// Общие модели API: используются сервером и мобильным приложением (app:shared).
// Даты передаются строками ISO-8601, например "2026-10-10T12:00:00+07:00".

@Serializable
enum class Role { CLIENT, OPERATOR, ADMIN }

@Serializable
enum class DevicePlatform { ANDROID, IOS }

@Serializable
data class DeviceInfo(
    val installationId: String,
    val platform: DevicePlatform,
    val appVersion: String? = null,
    val osVersion: String? = null,
    val pushToken: String? = null,
)

/** Вход клиента в мобильном приложении. */
@Serializable
data class MobileLoginRequest(
    val phone: String,
    val password: String,
    val device: DeviceInfo,
)

/** Вход сотрудника в веб-интерфейсе. */
@Serializable
data class OperatorLoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class UserDto(
    val id: Long,
    val role: Role,
    val fullName: String,
    val phone: String?,
    val email: String?,
)

@Serializable
data class AuthResponse(
    /** JWT, передаётся в заголовке `Authorization: Bearer <token>`. */
    val accessToken: String,
    val accessTokenExpiresIn: Long,
    /** Одноразовый: после /auth/refresh старый перестаёт работать. */
    val refreshToken: String,
    val user: UserDto,
)

@Serializable
data class ErrorResponse(val error: String, val message: String? = null)
