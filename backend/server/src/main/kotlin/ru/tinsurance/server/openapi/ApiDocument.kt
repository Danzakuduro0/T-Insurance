package ru.tinsurance.server.openapi

import kotlinx.serialization.serializer
import kotlinx.serialization.json.*
import ru.tinsurance.api.ErrorResponse

class ApiDocument {
    val schemas = SchemaRegistry()
    private val paths = linkedMapOf<String, MutableMap<String, JsonElement>>()
    inline fun <reified T> schema() = schemas.schema(serializer<T>().descriptor)

    fun operation(method: String, path: String, summary: String, response: JsonObject? = null,
        request: JsonObject? = null, codes: List<Int> = listOf(200), public: Boolean = false,
        query: List<JsonObject> = emptyList(), multipart: Boolean = false, binary: Boolean = false) {
        val security = if (public) JsonArray(emptyList()) else JsonArray(listOf(obj(
            (if (path.startsWith("/api/mobile")) "MobileBearer" else "OperatorBearer") to JsonArray(emptyList()))))
        val parameters = Regex("\\{([^}]+)}").findAll(path).map {
            obj("name" to str(it.groupValues[1]), "in" to str("path"), "required" to JsonPrimitive(true),
                "schema" to obj("type" to str("integer"), "format" to str("int64"), "minimum" to JsonPrimitive(1)))
        }.toList() + query
        val responses = codes.associate { code -> code.toString() to buildJsonObject {
            put("description", if (code == 204) "No content" else "Результат операции")
            if (response != null && code != 204) put("content", if (binary) JsonObject(listOf("image/jpeg", "image/png", "application/pdf").associateWith { obj("schema" to response) }) else content(response))
        } }.toMutableMap()
        responses["default"] = obj("description" to str("Ошибка: 400 — данные, 401 — вход, 403 — права, 404 — ресурс, 409 — конфликт, 413 — размер, 500 — сервер"),
            "content" to content(schema<ErrorResponse>()))
        if (path.contains("/auth/")) responses["429"] = obj("description" to str("Слишком много попыток. Тело может отсутствовать при ограничении частоты."),
            "headers" to obj("Retry-After" to obj("description" to str("Число секунд до повторной попытки"), "schema" to obj("type" to str("string")))))
        val operation = buildJsonObject {
            put("summary", summary)
            put("operationId", method + path.replace(Regex("[^a-zA-Z0-9]"), "_"))
            put("tags", strings(if (path.startsWith("/api/mobile")) "Клиент" else if (path.startsWith("/api/operator")) "Оператор и администратор" else "Система"))
            put("security", security); put("parameters", JsonArray(parameters)); put("responses", JsonObject(responses))
            if (request != null) put("requestBody", obj("required" to JsonPrimitive(true),
                "content" to content(request, if (multipart) "multipart/form-data" else "application/json")))
        }
        check(paths.getOrPut(path) { linkedMapOf() }.put(method, operation) == null)
    }

    fun build() = obj("openapi" to str("3.1.0"),
        "info" to obj("title" to str("Т-Страхование Backend API"), "version" to str("1.0.0"),
            "description" to str("Вымышленные страховые обращения. Решения о выплатах не принимаются. Даты — ISO 8601. Для мобильного и веб-канала нужны разные JWT; администратор использует веб-вход.")),
        "servers" to JsonArray(listOf(obj("url" to str("/")))),
        "paths" to JsonObject(paths.mapValues { JsonObject(it.value) }),
        "components" to obj("schemas" to JsonObject(schemas.definitions), "securitySchemes" to obj(
            "MobileBearer" to bearer("JWT мобильного входа"), "OperatorBearer" to bearer("JWT веб-входа; для dashboard и assign требуется ADMIN"))))

    private fun bearer(description: String) = obj("type" to str("http"), "scheme" to str("bearer"), "bearerFormat" to str("JWT"), "description" to str(description))
    private fun content(schema: JsonObject, mime: String = "application/json") = obj(mime to obj("schema" to schema))
}

internal fun parameter(name: String, type: String = "string", description: String = "") = obj(
    "name" to str(name), "in" to str("query"), "description" to str(description), "schema" to obj("type" to str(type)))
