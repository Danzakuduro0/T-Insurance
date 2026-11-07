package com.tinsurance.app

import com.tinsurance.app.client.fieldsUpdate
import com.tinsurance.app.client.isTextField
import com.tinsurance.app.client.itemSubtitle
import com.tinsurance.app.data.ApiException
import com.tinsurance.app.data.MobileApiClient
import com.tinsurance.app.data.mimeType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import ru.tinsurance.api.FileStatus
import ru.tinsurance.api.InfoRequestItemDto
import ru.tinsurance.api.MaterialIssue
import ru.tinsurance.api.RejectionReason

class InfoRequestTest {
    private fun fileJson(status: String, extra: String = "") =
        """{"id":9,"documentType":"DAMAGE_PHOTO","documentTypeName":"Фото повреждений",
           "originalName":"door.jpg","mimeType":"image/jpeg","sizeBytes":3,"status":"$status",
           "rejectionReason":${if (status == "REJECTED") "\"LOW_QUALITY\"" else "null"},
           "uploadedAt":"2026-11-03T10:00:00+07:00"$extra}"""

    private fun client(status: HttpStatusCode, body: String, seen: MutableList<String> = mutableListOf()) =
        MobileApiClient("http://test") { config ->
            HttpClient(
                MockEngine { request ->
                    val form = request.body.toByteArray().decodeToString()
                    seen += "${request.method.value} ${request.url.encodedPath}"
                    seen += form
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
                config,
            )
        }

    @Test
    fun uploadSendsMultipartFieldsAndReplacement() = runTest {
        val seen = mutableListOf<String>()
        val api = client(HttpStatusCode.Created, fileJson("ACCEPTED"), seen)
        val file = api.uploadFile(5, "DAMAGE_PHOTO", "door.jpg", byteArrayOf(1, 2, 3), replacesFileId = 42)
        assertEquals(FileStatus.ACCEPTED, file.status)
        assertEquals("POST /api/mobile/claims/5/files", seen[0])
        val form = seen[1]
        for (part in listOf("name=\"documentType\"", "DAMAGE_PHOTO", "name=\"replacesFileId\"", "42", "name=\"clientRequestId\"", "name=\"file\"", "filename=\"door.jpg\"", "image/jpeg")) {
            assertTrue(part in form, "multipart без «$part»")
        }
    }

    @Test
    fun rejectedFileIsResultNotError() = runTest {
        val api = client(HttpStatusCode.UnprocessableEntity, fileJson("REJECTED", ""","hint":"Снимите при дневном свете""""))
        val file = api.uploadFile(5, "DAMAGE_PHOTO", "door.jpg", byteArrayOf(1))
        assertEquals(FileStatus.REJECTED, file.status)
        assertEquals(RejectionReason.LOW_QUALITY, file.rejectionReason)
        assertEquals("Снимите при дневном свете", file.hint)
    }

    @Test
    fun tooLargeFileIsError() = runTest {
        val api = client(HttpStatusCode.PayloadTooLarge, """{"error":"FILE_TOO_LARGE","message":"Файл больше 20 МБ"}""")
        val error = assertFailsWith<ApiException> { api.uploadFile(5, "DAMAGE_PHOTO", "big.jpg", byteArrayOf(1)) }
        assertEquals("Файл больше 20 МБ", error.message)
    }

    @Test
    fun unfinishedRequestOnSubmitShowsServerMessage() = runTest {
        val api = client(HttpStatusCode.Conflict, """{"error":"CONFLICT","message":"Добавьте запрошенные материалы"}""")
        val error = assertFailsWith<ApiException> { api.submit(5) }
        assertEquals(409, error.status)
        assertEquals("Добавьте запрошенные материалы", error.message)
    }

    @Test
    fun requestedFieldsMapToUpdateRequest() {
        val update = fieldsUpdate(mapOf("description" to "Новое описание", "circumstances" to "Перестроение"))
        assertEquals("Новое описание", update.description)
        assertNull(update.eventLocation)
        assertEquals(JsonPrimitive("Перестроение"), update.details?.get("circumstances"))
        assertTrue(isTextField("description"))
        assertTrue(isTextField("circumstances"))
        assertFalse(isTextField("eventAt"))
        assertFalse(isTextField("policyId"))
    }

    @Test
    fun itemSubtitleCombinesIssueCommentAndCount() {
        val item =
            InfoRequestItemDto(
                documentType = "DAMAGE_PHOTO",
                documentTypeName = "Фото повреждений",
                fieldName = null,
                minCount = 2,
                comment = "Общий вид",
                issue = MaterialIssue.LOW_QUALITY,
                hint = null,
                fileId = null,
                isFulfilled = false,
            )
        assertEquals("Плохое качество · Общий вид · Не меньше 2 файла", itemSubtitle(item))
        assertEquals("image/png", mimeType("scan.PNG"))
        assertEquals("application/pdf", mimeType("notice.pdf"))
    }
}
