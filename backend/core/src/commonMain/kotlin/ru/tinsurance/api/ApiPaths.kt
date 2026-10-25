package ru.tinsurance.api

/**
 * Адреса API — общие для сервера и клиентов. Параметры подставляет [path]:
 * `MobileApi.CLAIM.path("id" to 42)` → `/api/mobile/claims/42`.
 */
object MobileApi {
    const val BASE = "/api/mobile"
    const val LOGIN = "$BASE/auth/login"
    const val REFRESH = "$BASE/auth/refresh"
    const val LOGOUT = "$BASE/auth/logout"
    const val ME = "$BASE/me"
    const val POLICIES = "$BASE/policies"
    const val EVENT_TYPES = "$BASE/event-types"
    const val CLAIMS = "$BASE/claims"
    const val CLAIM = "$CLAIMS/{id}"
    const val CLAIM_COMPLETENESS = "$CLAIM/completeness"
    const val CLAIM_SUBMIT = "$CLAIM/submit"
    const val CLAIM_TIMELINE = "$CLAIM/timeline"
    const val CLAIM_FILES = "$CLAIM/files"
    const val CLAIM_FILE_CONTENT = "$CLAIM_FILES/{fileId}/content"
}

object OperatorApi {
    const val BASE = "/api/operator"
    const val LOGIN = "$BASE/auth/login"
    const val REFRESH = "$BASE/auth/refresh"
    const val LOGOUT = "$BASE/auth/logout"
    const val ME = "$BASE/me"
    const val DIRECTIONS = "$BASE/directions"
    const val QUEUE = "$BASE/queue"
    const val CLAIM = "$BASE/claims/{id}"
    const val CLAIM_FILE_CONTENT = "$CLAIM/files/{fileId}/content"
    const val CLAIM_DECISION_CONFIRM = "$CLAIM/decisions/confirm"
    const val CLAIM_DECISION_OVERRIDE = "$CLAIM/decisions/override"
}

fun String.path(vararg params: Pair<String, Any>): String =
    params.fold(this) { acc, (name, value) -> acc.replace("{$name}", value.toString()) }
