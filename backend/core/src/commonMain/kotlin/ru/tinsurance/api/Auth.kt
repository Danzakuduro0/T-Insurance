package ru.tinsurance.api

import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfo(
    val installationId: String,
    val platform: DevicePlatform,
    val appVersion: String? = null,
    val osVersion: String? = null,
    val pushToken: String? = null,
)

@Serializable
data class MobileLoginRequest(
    val phone: String,
    val password: String,
    val device: DeviceInfo,
)

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
    /** Только у сотрудников: на смене ли он. */
    val availability: OperatorAvailability? = null,
)

@Serializable
data class AuthResponse(
    /** Передаётся в заголовке `Authorization: Bearer <token>`. */
    val accessToken: String,
    val accessTokenExpiresIn: Long,
    /** Одноразовый: после обновления старый перестаёт работать. */
    val refreshToken: String,
    val user: UserDto,
)
