package ru.tinsurance.server

import ru.tinsurance.server.analysis.AnalysisRequest
import ru.tinsurance.server.analysis.LocalImageAnalyzer
import ru.tinsurance.api.*
import ru.tinsurance.api.mobile.*
import ru.tinsurance.api.operator.*
import ru.tinsurance.server.demo.DemoData
import ru.tinsurance.server.files.LocalFileStorage
import ru.tinsurance.server.jobs.JobWorker
import ru.tinsurance.server.routing.PhotoAnalysis
import ru.tinsurance.server.routing.RoutingConfig
import ru.tinsurance.server.routing.RoutingInput
import ru.tinsurance.server.routing.RoutingRules
import com.sun.net.httpserver.HttpServer
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.*

/**
 * Шаг 3: фоновый анализ фото и маршрутизация после анализа (CHECK-004).
 * ML-сервис подменён локальным HTTP-сервером, который отвечает в формате раздела 6.2 требований.
 */
class AnalysisTest {

    // ---------------------------------------------------------------- сценарии

    @Test
    fun confidentModelSendsClaimToStandardQueue() = FakeMl().use { ml ->
        claimFlowTest("analysis.mlUrl" to ml.url) {
            val (claim, files) = readyClaim()
            assertEquals(ClaimStatus.IN_ANALYSIS, submit(claim.id).status)
            assertEquals("1", query("select count(*) from background_jobs where type = 'ROUTE_CLAIM' and status = 'PENDING' and payload ->> 'claimId' = '${claim.id}'"))

            runWorker()

            // запрос к ML — в формате раздела 6.2, без данных клиента
            val request = ml.requests.first { it["document_id"]!!.jsonPrimitive.content == files[0].id.toString() }
            assertEquals(claim.id.toString(), request["claim_id"]!!.jsonPrimitive.content)
            assertTrue(request["storage_key"]!!.jsonPrimitive.content.startsWith("claims/${claim.id}/"))

            assertEquals("QUEUED", status(claim.id))
            assertEquals("STANDARD", query("select direction_code from claims where id = ${claim.id}"))
            assertEquals("1", query("select priority from claims where id = ${claim.id}"))
            assertEquals("DENT", query("select value ->> 'damageType' from claim_decisions where claim_id = ${claim.id} and kind = 'DAMAGE' and is_current"))
            assertEquals("DONE", query("select analysis_status from claim_files where id = ${files[1].id}"))

            // оператор видит результат анализа каждого фото
            val operator = http.loginOperator().accessToken
            val card = http.get("/api/operator/claims/${claim.id}") { bearerAuth(operator) }.body<ClaimCard>()
            val analysis = card.files.first { it.file.id == files[0].id }.analysis
            assertNotNull(analysis)
            assertEquals("DENT", analysis.damageType)
            assertEquals("test-1.0", analysis.modelVersion)
        }
    }

    @Test
    fun lowConfidenceGoesToManualReviewWithRaisedPriority() = FakeMl().use { ml ->
        ml.respond = { 200 to FakeMl.ok("SCRATCH", 0.41) }
        claimFlowTest("analysis.mlUrl" to ml.url) {
            val (claim, _) = readyClaim()
            submit(claim.id)
            runWorker()

            assertEquals("QUEUED", status(claim.id))
            assertEquals("MANUAL_REVIEW", query("select direction_code from claims where id = ${claim.id}"))
            assertEquals("2", query("select priority from claims where id = ${claim.id}"))
            assertEquals("t", query("select requires_manual_review from claims where id = ${claim.id}"))
            val reason = query("select reason from claim_decisions where claim_id = ${claim.id} and kind = 'ROUTING' and is_current")!!
            assertTrue(reason.contains("41%") && reason.contains("65%"), reason)
        }
    }

    @Test
    fun unusablePhotoIsRejectedWithHintAndClientIsAskedToRetake() = FakeMl().use { ml ->
        claimFlowTest("analysis.mlUrl" to ml.url) {
            val claim = glassDraft()
            val good = upload(claim.id, noise(seed = 501))
            val blurry = upload(claim.id, noise(seed = 502))
            ml.respond = { req ->
                if (req["document_id"]!!.jsonPrimitive.content == blurry.id.toString()) 200 to FakeMl.badQuality("BLURRY")
                else 200 to FakeMl.ok("BROKEN_GLASS", 0.88)
            }
            // анализ идёт ещё в черновике: клиент узнаёт о плохом фото до отправки
            runWorker()
            val draftFile = claim(claim.id).files.first { it.id == blurry.id }
            assertEquals(FileStatus.REJECTED, draftFile.status)
            assertEquals(RejectionReason.LOW_QUALITY, draftFile.rejectionReason)
            assertTrue(draftFile.hint!!.contains("размыт"), draftFile.hint)
            assertEquals(FileStatus.ACCEPTED, claim(claim.id).files.first { it.id == good.id }.status)

            // отправка с плохим фото → запрос переснять именно его
            val sent = submit(claim.id)
            assertEquals(ClaimStatus.NEEDS_INFO, sent.status)
            val item = sent.openInfoRequest!!.items.single { it.fileId == blurry.id }
            assertEquals(MaterialIssue.LOW_QUALITY, item.issue)
            assertTrue(item.hint!!.contains("размыт"))

            // замена фото и повторная отправка → анализ → очередь
            upload(claim.id, noise(seed = 503), replaces = blurry.id)
            assertEquals(ClaimStatus.IN_ANALYSIS, submit(claim.id).status)
            runWorker()
            assertEquals("QUEUED", status(claim.id))
            assertEquals("STANDARD", query("select direction_code from claims where id = ${claim.id}"))
        }
    }

    @Test
    fun photoRejectedWhileClaimIsInAnalysisReturnsItToClient() = FakeMl().use { ml ->
        ml.respond = { 200 to FakeMl.badQuality("LOW_LIGHT") }
        claimFlowTest("analysis.mlUrl" to ml.url) {
            // фото загружены, отправка раньше, чем закончился анализ
            val (claim, _) = readyClaim()
            assertEquals(ClaimStatus.IN_ANALYSIS, submit(claim.id).status)
            runWorker()

            assertEquals("NEEDS_INFO", status(claim.id))
            val request = claim(claim.id).openInfoRequest!!
            assertEquals(2, request.items.count { it.issue == MaterialIssue.LOW_QUALITY })
            assertTrue(request.items.all { it.hint!!.contains("темно") })
        }
    }

    @Test
    fun unavailableMlIsRetriedThenClaimGoesToOperatorWithoutAnalysis() = FakeMl().use { ml ->
        ml.respond = { 503 to """{"error":"OVERLOADED"}""" }
        claimFlowTest("analysis.mlUrl" to ml.url) {
            val (claim, files) = readyClaim()
            submit(claim.id)

            worker.runReady()
            // первая неудача: повтор через 30 с (раздел 6.5), обращение ждёт
            val delay = query(
                "select extract(epoch from run_after - now())::int from background_jobs where dedup_key = 'ANALYZE_IMAGE:file:${files[0].id}'"
            )!!.toInt()
            assertTrue(delay in 25..31, "пауза до повтора: $delay с")
            assertEquals("IN_ANALYSIS", status(claim.id))

            runWorker(rounds = 4)
            // 1 запуск + 3 повтора, дальше — «анализ недоступен»
            assertEquals("4", query("select attempts from background_jobs where dedup_key = 'ANALYZE_IMAGE:file:${files[0].id}'"))
            assertEquals("FAILED", query("select status from background_jobs where dedup_key = 'ANALYZE_IMAGE:file:${files[0].id}'"))
            assertEquals("FAILED", query("select analysis_status from claim_files where id = ${files[0].id}"))
            assertEquals(8, ml.requests.size)

            // обращение не застряло: оператор обработает вручную
            assertEquals("QUEUED", status(claim.id))
            assertEquals("MANUAL_REVIEW", query("select direction_code from claims where id = ${claim.id}"))
            val reason = query("select reason from claim_decisions where claim_id = ${claim.id} and kind = 'ROUTING' and is_current")!!
            assertTrue(reason.contains("недоступен для 2 фото"), reason)
        }
    }

    @Test
    fun claimDoesNotWaitForSilentMlLongerThanLimit() = FakeMl().use { ml ->
        ml.respond = { 503 to "" }
        claimFlowTest("analysis.mlUrl" to ml.url, "analysis.maxWaitMinutes" to "0") {
            val (claim, files) = readyClaim()
            submit(claim.id)
            worker.runReady()

            // анализ ещё повторяется, но обращение уже у оператора с пометкой
            assertEquals("PENDING", query("select analysis_status from claim_files where id = ${files[0].id}"))
            assertEquals("QUEUED", status(claim.id))
            assertEquals("MANUAL_REVIEW", query("select direction_code from claims where id = ${claim.id}"))

            // поздний результат «плохое качество» уже не отклоняет файл: обращение у оператора, решает он
            ml.respond = { 200 to FakeMl.badQuality("BLURRY") }
            runWorker()
            assertEquals("DONE", query("select analysis_status from claim_files where id = ${files[0].id}"))
            assertEquals("ACCEPTED", query("select status from claim_files where id = ${files[0].id}"))
        }
    }

    @Test
    fun withoutMlServiceLocalCheckRejectsBlurryPhotoAndSendsRestToManualReview() =
        claimFlowTest {
            val claim = glassDraft()
            upload(claim.id, noise(seed = 601))
            val blurry = upload(claim.id, smooth())
            runWorker()

            val file = claim(claim.id).files.first { it.id == blurry.id }
            assertEquals(FileStatus.REJECTED, file.status)
            assertTrue(file.hint!!.contains("размыт"), file.hint)
            assertEquals(LocalImageAnalyzer.MODEL_VERSION, query("select model_version from image_analyses where file_id = ${blurry.id}"))

            upload(claim.id, noise(seed = 602), replaces = blurry.id)
            submit(claim.id)
            runWorker()
            // локальная проверка не определяет вид повреждения — решение за оператором
            assertEquals("QUEUED", status(claim.id))
            assertEquals("MANUAL_REVIEW", query("select direction_code from claims where id = ${claim.id}"))
        }

    @Test
    fun workerRecoversStuckJobsAndReschedulesForgottenClaims() = FakeMl().use { ml ->
        claimFlowTest("analysis.mlUrl" to ml.url) {
            val (claim, files) = readyClaim()
            submit(claim.id)
            // воркер «упал» посреди задачи, а задачу маршрутизации потеряли
            sql("update background_jobs set status = 'RUNNING', locked_at = now() - interval '1 hour', locked_by = 'dead' where dedup_key = 'ANALYZE_IMAGE:file:${files[0].id}'")
            sql("delete from background_jobs where type = 'ROUTE_CLAIM' and payload ->> 'claimId' = '${claim.id}'")

            worker.maintenance()
            assertEquals("PENDING", query("select status from background_jobs where dedup_key = 'ANALYZE_IMAGE:file:${files[0].id}'"))
            assertEquals("1", query("select count(*) from background_jobs where type = 'ROUTE_CLAIM' and status = 'PENDING' and payload ->> 'claimId' = '${claim.id}'"))
            sql("update background_jobs set status = 'DONE' where type = 'ROUTE_CLAIM' and status = 'PENDING' and payload ->> 'claimId' <> '${claim.id}'")

            runWorker()
            assertEquals("QUEUED", status(claim.id))
        }
    }

    // ---------------------------------------------------------------- локальная проверка и правила — без сервера

    @Test
    fun localAnalyzerFindsBlurDarknessAndLowResolution() {
        val dir = java.nio.file.Files.createTempDirectory("analysis-")
        val storage = LocalFileStorage(dir)
        val analyzer = LocalImageAnalyzer(storage)
        fun issues(bytes: ByteArray): List<String> {
            val key = "claims/1/${System.nanoTime()}.jpg"
            storage.put(key, bytes)
            return runBlocking { analyzer.analyze(AnalysisRequest(1, 1, key, "DAMAGE_PHOTO", "CAR_GLASS")) }.qualityIssues
        }
        assertEquals(emptyList(), issues(noise(seed = 1)))
        assertEquals(listOf("BLURRY"), issues(smooth()))
        assertTrue("LOW_LIGHT" in issues(noise(seed = 2, max = 40)))
        assertEquals(listOf("LOW_RESOLUTION"), issues(noise(seed = 3, w = 400, h = 300)))
        // файла нет в хранилище — ошибка без повторов
        assertFailsWith<ru.tinsurance.server.analysis.AnalyzerRejectedException> {
            runBlocking { analyzer.analyze(AnalysisRequest(1, 1, "claims/1/missing.jpg", "DAMAGE_PHOTO", null)) }
        }
    }

    @Test
    fun routingRulesExplainEveryManualReview() {
        val config = RoutingConfig(confidenceThreshold = 0.65f)
        fun photo(conf: Float?, status: AnalysisStatus = AnalysisStatus.DONE, type: String? = "DENT") = PhotoAnalysis(Random.nextLong(), status, type, conf)

        val ok = RoutingRules.decide(RoutingInput(listOf(photo(0.9f), photo(0.7f)), null), config)
        assertEquals("STANDARD", ok.direction)
        assertEquals(1, ok.priority)
        assertEquals(0.7f, ok.confidence)

        val atThreshold = RoutingRules.decide(RoutingInput(listOf(photo(0.65f)), null), config)
        assertEquals("STANDARD", atThreshold.direction)

        val duplicate = RoutingRules.decide(RoutingInput(listOf(photo(0.95f)), "TS-0000007"), config)
        assertEquals("MANUAL_REVIEW", duplicate.direction)
        assertTrue(duplicate.reasons.single().contains("TS-0000007"))

        val mixed = RoutingRules.decide(
            RoutingInput(listOf(photo(0.3f), photo(null, type = null), photo(null, status = AnalysisStatus.FAILED, type = null)), null), config,
        )
        assertEquals("MANUAL_REVIEW", mixed.direction)
        assertEquals(2, mixed.priority)
        assertEquals(3, mixed.reasons.size, mixed.reasons.toString())
    }
}
