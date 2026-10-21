package ru.tinsurance.server

import ru.tinsurance.server.analysis.AnalysisRequest
import ru.tinsurance.server.analysis.LocalImageAnalyzer
import ru.tinsurance.api.*
import ru.tinsurance.server.demo.DemoData
import ru.tinsurance.server.files.LocalFileStorage
import ru.tinsurance.server.jobs.JobWorker
import ru.tinsurance.server.operator.ClaimCard
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

    // ---------------------------------------------------------------- поддельный ML-сервис

    private class FakeMl : AutoCloseable {
        /** ответ на запрос: HTTP-код и тело; по умолчанию — фото годно, вмятина с уверенностью 90% */
        @Volatile var respond: (JsonObject) -> Pair<Int, String> = { 200 to ok("DENT", 0.9) }
        val requests = CopyOnWriteArrayList<JsonObject>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/analyze") { exchange ->
                val request = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                requests += request
                val (code, body) = respond(request)
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)

        companion object {
            fun ok(type: String, confidence: Double) =
                """{"document_id":"1","quality":{"score":0.9,"acceptable":true,"issues":[]},""" +
                    """"damage":{"type":"$type","confidence":$confidence,"top_classes":[{"label":"$type","confidence":$confidence}]},""" +
                    """"model_version":"test-1.0","processing_ms":5}"""
            fun badQuality(vararg issues: String) =
                """{"document_id":"1","quality":{"score":0.2,"acceptable":false,"issues":[${issues.joinToString { "\"$it\"" }}]},""" +
                    """"damage":null,"model_version":"test-1.0"}"""
        }
    }

    // ---------------------------------------------------------------- окружение теста

    private class Env(val http: HttpClient, val worker: JobWorker, val token: String, val userId: Long, val policyId: Long)

    /** Тест с воркером; задачи, оставшиеся от других тестов, снимаются, чтобы не мешать. */
    private fun analysisTest(vararg config: Pair<String, String>, block: suspend Env.() -> Unit) {
        lateinit var worker: JobWorker
        apiTest(TestDatabase.config(*config), extra = { worker = attributes[JobWorkerKey] }) { http ->
            // приложение (и миграции) стартует при первом запросе, поэтому очистка — после входа
            val auth = http.loginClient(DemoData.CLIENT_OLEG, installationId = "analysis-test")
            sql("update background_jobs set status = 'DONE' where status in ('PENDING', 'RUNNING')")
            val policy = http.get("/api/mobile/policies") { bearerAuth(auth.accessToken) }.body<List<PolicyDto>>().first()
            Env(http, worker, auth.accessToken, auth.user.id, policy.id).block()
        }
    }

    private suspend fun Env.glassDraft(): ClaimDetails = http.post("/api/mobile/claims") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        setBody(
            CreateClaimRequest(
                policyId = policyId, eventType = "CAR_GLASS", eventAt = OffsetDateTime.now().minusDays(1).toString(),
                eventLocation = "Новосибирск, ул. Гоголя, 1", description = "Скол на лобовом стекле",
            )
        )
    }.body()

    private suspend fun Env.upload(claimId: Long, bytes: ByteArray, replaces: Long? = null): ClaimFileDto =
        http.submitFormWithBinaryData(
            url = "/api/mobile/claims/$claimId/files",
            formData = formData {
                append("documentType", "DAMAGE_PHOTO")
                replaces?.let { append("replacesFileId", it.toString()) }
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"photo.jpg\"")
                    append(HttpHeaders.ContentType, "image/jpeg")
                })
            },
        ) { bearerAuth(token) }.body()

    private suspend fun Env.submit(claimId: Long): ClaimDetails =
        http.post("/api/mobile/claims/$claimId/submit") { bearerAuth(token) }.body()

    private suspend fun Env.claim(claimId: Long): ClaimDetails =
        http.get("/api/mobile/claims/$claimId") { bearerAuth(token) }.body()

    /** Готовое к отправке обращение о повреждении стекла: два фото повреждений. */
    private suspend fun Env.readyClaim(): Pair<ClaimDetails, List<ClaimFileDto>> {
        val claim = glassDraft()
        val files = List(2) { upload(claim.id, noise(seed = Random.nextInt())) }
        return claim to files
    }

    /**
     * Прогнать воркер, «перемотав» паузы: задачи, отложенные на потом, запускаются сразу.
     * Два прохода по умолчанию: маршрутизация могла проверить обращение раньше, чем закончился анализ.
     */
    private suspend fun Env.runWorker(rounds: Int = 2) = repeat(rounds) {
        sql("update background_jobs set run_after = now() where status = 'PENDING'")
        worker.runReady()
    }

    // ---------------------------------------------------------------- тестовые изображения

    /** Резкий кадр нормальной яркости: шум 800×600 (у шума перцептивного хэша нет — дублей не будет). */
    private fun noise(seed: Int, w: Int = 800, h: Int = 600, max: Int = 255): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val rnd = Random(seed)
        for (x in 0 until w) for (y in 0 until h) {
            val v = rnd.nextInt(max + 1)
            img.setRGB(x, y, (v shl 16) or (v shl 8) or v)
        }
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    /** Размытый кадр: плавный градиент без резких перепадов. */
    private fun smooth(w: Int = 800, h: Int = 600): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until w) for (y in 0 until h) {
            val v = 60 + (x * 140 / w)
            img.setRGB(x, y, (v shl 16) or (v shl 8) or v)
        }
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    private fun sql(statement: String) = TestDatabase.connection().use { it.createStatement().execute(statement) }

    private fun query(sql: String): String? = TestDatabase.connection().use { c ->
        c.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
    }

    private fun status(claimId: Long) = query("select status from claims where id = $claimId")

    // ---------------------------------------------------------------- сценарии

    @Test
    fun confidentModelSendsClaimToStandardQueue() = FakeMl().use { ml ->
        analysisTest("analysis.mlUrl" to ml.url) {
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
        analysisTest("analysis.mlUrl" to ml.url) {
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
        analysisTest("analysis.mlUrl" to ml.url) {
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
            assertEquals("REJECTED", draftFile.status)
            assertEquals("LOW_QUALITY", draftFile.rejectionReason)
            assertTrue(draftFile.hint!!.contains("размыт"), draftFile.hint)
            assertEquals("ACCEPTED", claim(claim.id).files.first { it.id == good.id }.status)

            // отправка с плохим фото → запрос переснять именно его
            val sent = submit(claim.id)
            assertEquals(ClaimStatus.NEEDS_INFO, sent.status)
            val item = sent.openInfoRequest!!.items.single { it.fileId == blurry.id }
            assertEquals("LOW_QUALITY", item.issue)
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
        analysisTest("analysis.mlUrl" to ml.url) {
            // фото загружены, отправка раньше, чем закончился анализ
            val (claim, _) = readyClaim()
            assertEquals(ClaimStatus.IN_ANALYSIS, submit(claim.id).status)
            runWorker()

            assertEquals("NEEDS_INFO", status(claim.id))
            val request = claim(claim.id).openInfoRequest!!
            assertEquals(2, request.items.count { it.issue == "LOW_QUALITY" })
            assertTrue(request.items.all { it.hint!!.contains("темно") })
        }
    }

    @Test
    fun unavailableMlIsRetriedThenClaimGoesToOperatorWithoutAnalysis() = FakeMl().use { ml ->
        ml.respond = { 503 to """{"error":"OVERLOADED"}""" }
        analysisTest("analysis.mlUrl" to ml.url) {
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
        analysisTest("analysis.mlUrl" to ml.url, "analysis.maxWaitMinutes" to "0") {
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
        analysisTest {
            val claim = glassDraft()
            upload(claim.id, noise(seed = 601))
            val blurry = upload(claim.id, smooth())
            runWorker()

            val file = claim(claim.id).files.first { it.id == blurry.id }
            assertEquals("REJECTED", file.status)
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
        analysisTest("analysis.mlUrl" to ml.url) {
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
        fun photo(conf: Float?, status: String = "DONE", type: String? = "DENT") = PhotoAnalysis(Random.nextLong(), status, type, conf)

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
            RoutingInput(listOf(photo(0.3f), photo(null, type = null), photo(null, status = "FAILED", type = null)), null), config,
        )
        assertEquals("MANUAL_REVIEW", mixed.direction)
        assertEquals(2, mixed.priority)
        assertEquals(3, mixed.reasons.size, mixed.reasons.toString())
    }
}
