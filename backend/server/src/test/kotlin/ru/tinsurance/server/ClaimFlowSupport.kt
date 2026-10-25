package ru.tinsurance.server

import com.sun.net.httpserver.HttpServer
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import ru.tinsurance.api.ClaimFileDto
import ru.tinsurance.api.PolicyDto
import ru.tinsurance.api.mobile.ClaimDetails
import ru.tinsurance.api.mobile.CreateClaimRequest
import ru.tinsurance.server.demo.DemoData
import ru.tinsurance.server.jobs.JobWorker
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.random.Random

// Сценарий «обращение прошло анализ фото и маршрутизацию»: поддельный ML-сервис, клиент Олег, фоновый воркер.

// ---------------------------------------------------------------- поддельный ML-сервис

internal class FakeMl : AutoCloseable {
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

internal class ClaimFlow(val http: HttpClient, val worker: JobWorker, val token: String, val userId: Long, val policyId: Long)

/** Тест с воркером; задачи, оставшиеся от других тестов, снимаются, чтобы не мешать. */
internal fun claimFlowTest(vararg config: Pair<String, String>, block: suspend ClaimFlow.() -> Unit) {
    lateinit var worker: JobWorker
    apiTest(TestDatabase.config(*config), extra = { worker = attributes[JobWorkerKey] }) { http ->
        // приложение (и миграции) стартует при первом запросе, поэтому очистка — после входа
        val auth = http.loginClient(DemoData.CLIENT_OLEG, installationId = "analysis-test")
        sql("update background_jobs set status = 'DONE' where status in ('PENDING', 'RUNNING')")
        val policy = http.get("/api/mobile/policies") { bearerAuth(auth.accessToken) }.body<List<PolicyDto>>().first()
        ClaimFlow(http, worker, auth.accessToken, auth.user.id, policy.id).block()
    }
}

internal suspend fun ClaimFlow.glassDraft(): ClaimDetails = http.post("/api/mobile/claims") {
    bearerAuth(token); contentType(ContentType.Application.Json)
    setBody(
        CreateClaimRequest(
            policyId = policyId, eventType = "CAR_GLASS", eventAt = OffsetDateTime.now().minusDays(1).toString(),
            eventLocation = "Новосибирск, ул. Гоголя, 1", description = "Скол на лобовом стекле",
        )
    )
}.body()

internal suspend fun ClaimFlow.upload(claimId: Long, bytes: ByteArray, replaces: Long? = null): ClaimFileDto =
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

internal suspend fun ClaimFlow.submit(claimId: Long): ClaimDetails =
    http.post("/api/mobile/claims/$claimId/submit") { bearerAuth(token) }.body()

internal suspend fun ClaimFlow.claim(claimId: Long): ClaimDetails =
    http.get("/api/mobile/claims/$claimId") { bearerAuth(token) }.body()

/** Готовое к отправке обращение о повреждении стекла: два фото повреждений. */
internal suspend fun ClaimFlow.readyClaim(): Pair<ClaimDetails, List<ClaimFileDto>> {
    val claim = glassDraft()
    val files = List(2) { upload(claim.id, noise(seed = Random.nextInt())) }
    return claim to files
}

/**
 * Прогнать воркер, «перемотав» паузы: задачи, отложенные на потом, запускаются сразу.
 * Два прохода по умолчанию: маршрутизация могла проверить обращение раньше, чем закончился анализ.
 */
internal suspend fun ClaimFlow.runWorker(rounds: Int = 2) = repeat(rounds) {
    sql("update background_jobs set run_after = now() where status = 'PENDING'")
    worker.runReady()
}

// ---------------------------------------------------------------- тестовые изображения

/** Резкий кадр нормальной яркости: шум 800×600 (у шума перцептивного хэша нет — дублей не будет). */
internal fun noise(seed: Int, w: Int = 800, h: Int = 600, max: Int = 255): ByteArray {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val rnd = Random(seed)
    for (x in 0 until w) for (y in 0 until h) {
        val v = rnd.nextInt(max + 1)
        img.setRGB(x, y, (v shl 16) or (v shl 8) or v)
    }
    return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
}

/** Размытый кадр: плавный градиент без резких перепадов. */
internal fun smooth(w: Int = 800, h: Int = 600): ByteArray {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until w) for (y in 0 until h) {
        val v = 60 + (x * 140 / w)
        img.setRGB(x, y, (v shl 16) or (v shl 8) or v)
    }
    return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
}

internal fun sql(statement: String) = TestDatabase.connection().use { it.createStatement().execute(statement) }

internal fun query(sql: String): String? = TestDatabase.connection().use { c ->
    c.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
}

internal fun status(claimId: Long) = query("select status from claims where id = $claimId")
