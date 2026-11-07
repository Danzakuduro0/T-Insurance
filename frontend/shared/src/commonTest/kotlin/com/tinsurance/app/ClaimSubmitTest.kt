package com.tinsurance.app

import com.tinsurance.app.client.ReviewState
import com.tinsurance.app.client.photosToReview
import com.tinsurance.app.data.ApiException
import com.tinsurance.app.data.MobileApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import ru.tinsurance.api.ClaimFileDto
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.FileStatus
import ru.tinsurance.api.mobile.ClaimDetails
import ru.tinsurance.api.mobile.CompletenessDto
import ru.tinsurance.api.mobile.FormDocumentDto
import ru.tinsurance.api.mobile.MissingDocumentDto
import ru.tinsurance.api.mobile.MissingFieldDto

class ClaimSubmitTest {
    private val photos = FormDocumentDto("DAMAGE_PHOTO", "Фото повреждений", 3, listOf("image/jpeg", "image/png"), 20)
    private val notice = FormDocumentDto("ACCIDENT_NOTICE", "Извещение", 1, listOf("image/jpeg", "application/pdf"), 20)

    private fun file(id: Long, type: String, status: FileStatus) =
        ClaimFileDto(id, type, type, "f$id.jpg", "image/jpeg", 1, status, null, "2026-11-07T10:00:00Z")

    private fun draft(files: List<ClaimFileDto>) =
        ClaimDetails(
            id = 7, claimNumber = "TS-0000007", status = ClaimStatus.DRAFT, statusTitle = "Черновик",
            isFinal = false, policyId = 1, policyNumber = "KASKO-1", eventType = "CAR_ACCIDENT",
            eventTypeName = "ДТП", eventAt = null, eventLocation = null, description = null,
            contactPhone = null, participants = emptyList(), details = JsonObject(emptyMap()),
            createdAt = "2026-11-07T10:00:00Z", updatedAt = "2026-11-07T10:00:00Z", submittedAt = null,
            openInfoRequest = null, files = files,
        )

    @Test
    fun qualityScreenShowsOnlyRejectedOrCheckingPhotos() {
        val claim =
            draft(
                listOf(
                    file(1, "DAMAGE_PHOTO", FileStatus.ACCEPTED),
                    file(2, "DAMAGE_PHOTO", FileStatus.REJECTED),
                    file(3, "DAMAGE_PHOTO", FileStatus.UPLOADED),
                    file(4, "DAMAGE_PHOTO", FileStatus.DELETED),
                    // Извещение может быть PDF — это не фото, его экран 05 не показывает.
                    file(5, "ACCIDENT_NOTICE", FileStatus.REJECTED),
                )
            )
        assertEquals(listOf(2L, 3L), claim.photosToReview(listOf(photos, notice)).map { it.id })
    }

    @Test
    fun missingFieldsBlockSubmitButMissingDocumentsDoNot() {
        val noDocs = CompletenessDto(false, emptyList(), listOf(MissingDocumentDto("ACCIDENT_NOTICE", "Извещение", 1, 0)), emptyList())
        val noFields = noDocs.copy(missingFields = listOf(MissingFieldDto("eventLocation", "Место события")))
        // Без документов сервер примет обращение и сразу запросит недостающее.
        assertFalse(ReviewState(completeness = noDocs).blocked)
        assertTrue(ReviewState(completeness = noFields).blocked)
        assertFalse(ReviewState().blocked)
    }

    @Test
    fun completenessAndSubmitUseClaimPaths() = runTest {
        val paths = mutableListOf<String>()
        val api =
            MobileApiClient("http://test") { config ->
                HttpClient(
                    MockEngine { request ->
                        paths += "${request.method.value} ${request.url.encodedPath}"
                        if (request.url.encodedPath.endsWith("/submit"))
                            respond(
                                """{"error":"INCOMPLETE_DRAFT","message":"Перед отправкой заполните: Место события"}""",
                                HttpStatusCode.BadRequest,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        else
                            respond(
                                """{"complete":true,"missingFields":[],"missingDocuments":[],"problemFiles":[]}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                    },
                    config,
                )
            }
        assertTrue(api.completeness(7).complete)
        val error = assertFailsWith<ApiException> { api.submit(7) }
        assertEquals("Перед отправкой заполните: Место события", error.message)
        assertEquals(
            listOf("GET /api/mobile/claims/7/completeness", "POST /api/mobile/claims/7/submit"),
            paths,
        )
    }
}
