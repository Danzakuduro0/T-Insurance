package ru.tinsurance.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import ru.tinsurance.api.MobileApi
import ru.tinsurance.api.OperatorApi
import ru.tinsurance.server.openapi.buildOpenApi
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class OpenApiTest {
    private val document get() = buildOpenApi()

    @Test
    fun documentAndLocalSwaggerAssetsAreServedWithoutChangingApiAuthentication() = apiTest { http ->
        val result = http.get("/openapi.json")
        assertEquals(HttpStatusCode.OK, result.status)
        assertEquals("3.1.0", Json.parseToJsonElement(result.bodyAsText()).jsonObject.getValue("openapi").jsonPrimitive.content)
        for (url in listOf("/swagger/index.html", "/swagger/swagger-ui.css", "/swagger/swagger-ui-bundle.js")) {
            val response = http.get(url)
            assertEquals(HttpStatusCode.OK, response.status, url)
            assertTrue(response.bodyAsText().isNotBlank())
        }
        assertEquals(HttpStatusCode.Unauthorized, http.get(MobileApi.CLAIMS).status)
        Files.createDirectories(Path.of("build"))
        Files.writeString(Path.of("build/openapi.json"), result.bodyAsText())
    }

    @Test
    fun everyImplementedRouteAndSharedPathIsDocumented() {
        val paths = document.getValue("paths").jsonObject
        for (type in listOf(MobileApi::class.java, OperatorApi::class.java)) {
            for (field in type.fields.filter { it.type == String::class.java && it.name != "BASE" }) {
                assertTrue(field.get(null) in paths, field.name)
            }
        }
        val regex = Regex("(get|post|put|patch|delete)\\((MobileApi|OperatorApi)\\.([A-Z_]+)\\)")
        Files.walk(Path.of("src/main/kotlin/ru/tinsurance/server")).use { files ->
            files.filter { it.toString().endsWith("Routes.kt") }.forEach { file ->
                regex.findAll(Files.readString(file)).forEach { match ->
                    val type = if (match.groupValues[2] == "MobileApi") MobileApi::class.java else OperatorApi::class.java
                    val path = type.getField(match.groupValues[3]).get(null) as String
                    assertTrue(match.groupValues[1] in paths.getValue(path).jsonObject, "$path ${match.groupValues[1]}")
                }
            }
        }
        val ids = paths.values.flatMap { it.jsonObject.values }.map { it.jsonObject.getValue("operationId").jsonPrimitive.content }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun schemasPreserveRequiredNullableEnumsAndAllReferencesResolve() {
        val doc = document
        val schemas = doc.getValue("components").jsonObject.getValue("schemas").jsonObject
        val user = schemas.getValue("UserDto").jsonObject
        assertTrue(JsonPrimitive("phone") in user.getValue("required").jsonArray)
        assertTrue("anyOf" in user.getValue("properties").jsonObject.getValue("phone").jsonObject)
        val draft = schemas.getValue("CreateClaimRequest").jsonObject
        assertTrue(draft.getValue("required").jsonArray.isEmpty())
        val status = schemas.getValue("ClaimDetails").jsonObject.getValue("properties").jsonObject.getValue("status").jsonObject
        assertTrue(JsonPrimitive("NEEDS_INFO") in status.getValue("enum").jsonArray)
        fun check(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    element["\$ref"]?.jsonPrimitive?.content?.let { assertTrue(it.substringAfterLast('/') in schemas, it) }
                    element.values.forEach(::check)
                }
                is JsonArray -> element.forEach(::check)
                else -> Unit
            }
        }
        check(doc)
    }

    @Test
    fun uploadResponsesAndSeparateJwtChannelsAreDocumented() {
        val paths = document.getValue("paths").jsonObject
        fun operation(path: String, method: String) = paths.getValue(path).jsonObject.getValue(method).jsonObject
        val upload = operation(MobileApi.CLAIM_FILES, "post")
        assertTrue("multipart/form-data" in upload.getValue("requestBody").jsonObject.getValue("content").jsonObject)
        assertTrue("422" in upload.getValue("responses").jsonObject)
        assertTrue(operation(MobileApi.LOGIN, "post").getValue("security").jsonArray.isEmpty())
        assertTrue("MobileBearer" in operation(MobileApi.CLAIMS, "get").getValue("security").jsonArray[0].jsonObject)
        assertTrue("OperatorBearer" in operation(OperatorApi.DASHBOARD, "get").getValue("security").jsonArray[0].jsonObject)
        assertFalse("content" in operation(MobileApi.LOGOUT, "post").getValue("responses").jsonObject.getValue("204").jsonObject)
    }
}
