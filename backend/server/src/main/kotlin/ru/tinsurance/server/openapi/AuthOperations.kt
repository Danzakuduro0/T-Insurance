package ru.tinsurance.server.openapi

import ru.tinsurance.api.*
import ru.tinsurance.api.mobile.*
import ru.tinsurance.api.operator.*
import kotlinx.serialization.json.*

internal fun ApiDocument.authOperations() {
    operation("post", MobileApi.LOGIN, "Вход", schema<AuthResponse>(), schema<MobileLoginRequest>(), public = true)
    operation("post", MobileApi.REFRESH, "Ротация refresh-токена", schema<AuthResponse>(), schema<RefreshRequest>(), public = true)
    operation("post", MobileApi.LOGOUT, "Выход и отзыв сессии", codes = listOf(204))
    operation("post", OperatorApi.LOGIN, "Вход", schema<AuthResponse>(), schema<OperatorLoginRequest>(), public = true)
    operation("post", OperatorApi.REFRESH, "Ротация refresh-токена", schema<AuthResponse>(), schema<RefreshRequest>(), public = true)
    operation("post", OperatorApi.LOGOUT, "Выход и отзыв сессии", codes = listOf(204))
}
