package ru.tinsurance.server

import ru.tinsurance.api.*
import ru.tinsurance.server.demo.DemoData
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import java.util.UUID
import kotlin.test.*

class MobileApiTest {

    @Test
    fun createDraftIsIdempotentAndAppearsInListAndTimeline() = apiTest { client ->
        val token = client.loginClient().accessToken
        val policy = client.get("/api/mobile/policies") { bearerAuth(token) }.body<List<PolicyDto>>().first()
        val request = CreateClaimRequest(
            policyId = policy.id,
            eventType = "CAR_GLASS",
            eventAt = "2026-10-09T18:30:00+07:00",
            description = "Скол на лобовом стекле",
            clientRequestId = UUID.randomUUID().toString(),
        )

        val first = client.post("/api/mobile/claims") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(request)
        }
        assertEquals(HttpStatusCode.Created, first.status)
        val claim = first.body<ClaimDetails>()
        assertEquals(ClaimStatus.DRAFT, claim.status)
        assertEquals("Черновик", claim.statusTitle)
        assertTrue(claim.claimNumber.startsWith("TS-"))

        // повтор с тем же clientRequestId (плохая сеть) — то же обращение, без дубля
        val retry = client.post("/api/mobile/claims") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(request)
        }
        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(claim.id, retry.body<ClaimDetails>().id)

        val list = client.get("/api/mobile/claims") { bearerAuth(token) }.body<List<ClaimListItem>>()
        assertEquals(1, list.count { it.id == claim.id })

        val timeline = client.get("/api/mobile/claims/${claim.id}/timeline") { bearerAuth(token) }.body<List<TimelineEvent>>()
        assertEquals(listOf(ClaimStatus.DRAFT), timeline.map { it.status })
    }

    @Test
    fun clientSeesNeedsInfoClaimWithChecklist() = apiTest { client ->
        val token = client.loginClient().accessToken
        val needsInfo = client.get("/api/mobile/claims") { bearerAuth(token) }.body<List<ClaimListItem>>()
            .single { it.status == ClaimStatus.NEEDS_INFO }
        assertTrue(needsInfo.actionRequired)

        val details = client.get("/api/mobile/claims/${needsInfo.id}") { bearerAuth(token) }.body<ClaimDetails>()
        val missing = details.openInfoRequest!!.items.map { it.documentType }
        assertEquals(listOf("ACCIDENT_NOTICE"), missing)

        val timeline = client.get("/api/mobile/claims/${needsInfo.id}/timeline") { bearerAuth(token) }.body<List<TimelineEvent>>()
        assertEquals(listOf(ClaimStatus.DRAFT, ClaimStatus.SUBMITTED, ClaimStatus.NEEDS_INFO), timeline.map { it.status })
    }

    @Test
    fun clientCannotSeeSomeoneElsesClaimOrUseForeignPolicy() = apiTest { client ->
        val ivan = client.loginClient(DemoData.CLIENT_IVAN).accessToken
        val anna = client.loginClient(DemoData.CLIENT_ANNA, installationId = "anna-phone").accessToken
        val annaClaim = client.get("/api/mobile/claims") { bearerAuth(anna) }.body<List<ClaimListItem>>().first()
        val annaPolicy = client.get("/api/mobile/policies") { bearerAuth(anna) }.body<List<PolicyDto>>().first()

        assertEquals(HttpStatusCode.NotFound, client.get("/api/mobile/claims/${annaClaim.id}") { bearerAuth(ivan) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/mobile/claims/${annaClaim.id}/timeline") { bearerAuth(ivan) }.status)

        val foreignPolicy = client.post("/api/mobile/claims") {
            bearerAuth(ivan); contentType(ContentType.Application.Json); setBody(CreateClaimRequest(policyId = annaPolicy.id))
        }
        assertEquals(HttpStatusCode.BadRequest, foreignPolicy.status)
    }
}
