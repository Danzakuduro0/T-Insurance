package ru.tinsurance.server

import ru.tinsurance.api.*
import ru.tinsurance.api.mobile.*
import ru.tinsurance.api.operator.*
import ru.tinsurance.server.demo.DemoData
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.tinsurance.server.files.FileInspector
import ru.tinsurance.server.files.PerceptualHash
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.util.UUID
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.*

/** Шаг 2: загрузка материалов (CHECK-003). Клиент — Анна. */
class FilesTest {

    // ---------------------------------------------------------------- тестовые файлы

    private fun image(format: String, seed: Int, w: Int = 64, h: Int = 48): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val rnd = Random(seed)
        for (x in 0 until w) for (y in 0 until h) img.setRGB(x, y, rnd.nextInt(0xFFFFFF))
        return ByteArrayOutputStream().also { ImageIO.write(img, format, it) }.toByteArray()
    }

    private fun jpeg(seed: Int) = image("jpg", seed)
    private fun png(seed: Int) = image("png", seed)

    /** «Снимок» со структурой — градиент и фигуры: у случайного шума перцептивный хэш не имеет смысла. */
    private fun scene(seed: Int, w: Int = 640, h: Int = 480): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val rnd = Random(seed)
        val g = img.createGraphics()
        g.paint = GradientPaint(0f, 0f, Color(rnd.nextInt(0xFFFFFF)), w.toFloat(), h.toFloat(), Color(rnd.nextInt(0xFFFFFF)))
        g.fillRect(0, 0, w, h)
        repeat(6) {
            g.color = Color(rnd.nextInt(0xFFFFFF))
            g.fillOval(rnd.nextInt(w), rnd.nextInt(h), 60 + rnd.nextInt(w / 2), 60 + rnd.nextInt(h / 2))
        }
        g.dispose()
        return img
    }

    private fun BufferedImage.encoded(format: String = "jpg") = ByteArrayOutputStream().also { ImageIO.write(this, format, it) }.toByteArray()

    private fun BufferedImage.resized(w: Int, h: Int): BufferedImage = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also {
        val g = it.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(this, 0, 0, w, h, null)
        g.dispose()
    }

    /** Поворот на 90° по часовой — так мессенджер «применяет» EXIF-ориентацию 6 к пикселям. */
    private fun BufferedImage.rotatedClockwise(): BufferedImage = BufferedImage(height, width, BufferedImage.TYPE_INT_RGB).also {
        for (x in 0 until width) for (y in 0 until height) it.setRGB(height - 1 - y, x, getRGB(x, y))
    }

    private fun phash(bytes: ByteArray): Long? = FileInspector(50_000_000).inspect(bytes, listOf("image/jpeg", "image/png")).phash

    /** JPEG с EXIF: ориентация 6 и «геолокация» (маркер SECRET-GPS), вставлен сразу после SOI. */
    private fun jpegWithExif(seed: Int): ByteArray = withExif(jpeg(seed))

    private fun withExif(base: ByteArray): ByteArray {
        val tiff = byteArrayOf(
            'I'.code.toByte(), 'I'.code.toByte(), 0x2A, 0, 8, 0, 0, 0,
            2, 0,
            0x12, 0x01, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0,             // Orientation = 6
            0x25, 0x88.toByte(), 4, 0, 1, 0, 0, 0, 38, 0, 0, 0,  // GPSInfo → смещение 38
            0, 0, 0, 0,
        ) + "SECRET-GPS-55.0302,82.9204".toByteArray()
        val data = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val len = data.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + data
        return base.copyOfRange(0, 2) + app1 + base.copyOfRange(2, base.size)
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray()
        val crc = CRC32().apply { update(typeBytes); update(data) }.value
        fun int(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        return int(data.size.toLong()) + typeBytes + data + int(crc)
    }

    /** PNG с текстовым блоком (tEXt) после IHDR. */
    private fun pngWithText(seed: Int): ByteArray {
        val base = png(seed)
        val ihdrEnd = 8 + 25
        return base.copyOfRange(0, ihdrEnd) + pngChunk("tEXt", "Comment\u0000SECRET-TEXT".toByteArray()) + base.copyOfRange(ihdrEnd, base.size)
    }

    /** «Бомба»: заголовок PNG 30000×30000 при крошечном файле. */
    private fun pngBomb(): ByteArray {
        val ihdr = byteArrayOf(0, 0, 0x75, 0x30, 0, 0, 0x75, 0x30, 8, 2, 0, 0, 0)
        val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        return signature + pngChunk("IHDR", ihdr) + pngChunk("IDAT", ByteArray(16)) + pngChunk("IEND", ByteArray(0))
    }

    private fun pdf(extra: String = "") = "%PDF-1.4\n1 0 obj << /Type /Catalog $extra >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n".toByteArray()

    // ---------------------------------------------------------------- помощники API

    private class Session(val http: HttpClient, val token: String, val userId: Long, val policyId: Long)

    private suspend fun HttpClient.anna(): Session = client(DemoData.CLIENT_ANNA)

    private suspend fun HttpClient.client(phone: String): Session {
        val auth = loginClient(phone, installationId = "files-test")
        val policy = get("/api/mobile/policies") { bearerAuth(auth.accessToken) }.body<List<PolicyDto>>().first()
        return Session(this, auth.accessToken, auth.user.id, policy.id)
    }

    private suspend fun Session.accidentDraft(): ClaimDetails = http.post("/api/mobile/claims") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        setBody(
            CreateClaimRequest(
                policyId = policyId, eventType = "CAR_ACCIDENT", eventAt = OffsetDateTime.now().minusHours(3).toString(),
                eventLocation = "Новосибирск, ул. Ленина, 5", description = "Столкновение",
                participants = listOf(ParticipantInput("APPLICANT_DRIVER", "Анна Смирнова")),
                details = buildJsonObject { put("circumstances", "Не уступил дорогу") },
            )
        )
    }.body()

    private suspend fun Session.upload(
        claimId: Long, type: String, bytes: ByteArray, name: String = "photo.jpg",
        requestId: String? = null, replaces: Long? = null,
    ): HttpResponse = http.submitFormWithBinaryData(
        url = "/api/mobile/claims/$claimId/files",
        formData = formData {
            append("documentType", type)
            requestId?.let { append("clientRequestId", it) }
            replaces?.let { append("replacesFileId", it.toString()) }
            append("file", bytes, Headers.build {
                append(HttpHeaders.ContentDisposition, "filename=\"$name\"")
                append(HttpHeaders.ContentType, "application/octet-stream")
            })
        },
    ) { bearerAuth(token) }

    private fun query(sql: String): String? = TestDatabase.connection().use { c ->
        c.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
    }

    private fun contains(haystack: ByteArray, needle: String) = String(haystack, Charsets.ISO_8859_1).contains(needle)

    // ---------------------------------------------------------------- тесты

    @Test
    fun acceptedPhotoIsStoredQueuedForAnalysisAndOnlyOwnerCanRead() = apiTest { http ->
        val s = http.anna()
        val claim = s.accidentDraft()
        val response = s.upload(claim.id, "DAMAGE_PHOTO", jpeg(1))
        assertEquals(HttpStatusCode.Created, response.status)
        val file = response.body<ClaimFileDto>()
        assertEquals(FileStatus.ACCEPTED, file.status)
        assertEquals("image/jpeg", file.mimeType)

        // вопрос 50–51: анализ — в фоне, задача поставлена
        assertEquals("PENDING", query("select analysis_status from claim_files where id = ${file.id}"))
        assertEquals("1", query("select count(*) from background_jobs where dedup_key = 'ANALYZE_IMAGE:file:${file.id}'"))
        assertEquals("FILE_ADD", query("select action from audit_log where entity_type = 'FILE' and entity_id = ${file.id}"))

        val content = http.get("/api/mobile/claims/${claim.id}/files/${file.id}/content") { bearerAuth(s.token) }
        assertEquals(HttpStatusCode.OK, content.status)
        assertEquals(ContentType.Image.JPEG, content.contentType()?.withoutParameters())
        assertEquals("nosniff", content.headers["X-Content-Type-Options"])
        assertTrue(content.headers[HttpHeaders.ContentDisposition]!!.contains("claim-${claim.id}-file-${file.id}.jpg"))
        assertNotNull(ImageIO.read(content.readRawBytes().inputStream()))

        // чужой клиент не видит; оператор не видит файлы черновика
        val ivan = http.loginClient(DemoData.CLIENT_IVAN, installationId = "files-ivan").accessToken
        assertEquals(HttpStatusCode.NotFound, http.get("/api/mobile/claims/${claim.id}/files/${file.id}/content") { bearerAuth(ivan) }.status)
        val operator = http.loginOperator().accessToken
        assertEquals(HttpStatusCode.NotFound, http.get("/api/operator/claims/${claim.id}/files/${file.id}/content") { bearerAuth(operator) }.status)
        // и чужой не может загрузить в чужое обращение
        val foreign = s.upload(claim.id, "DAMAGE_PHOTO", jpeg(2)).let { it.status }
        assertEquals(HttpStatusCode.Created, foreign)
        assertEquals(HttpStatusCode.NotFound, http.submitFormWithBinaryData("/api/mobile/claims/${claim.id}/files", formData {
            append("documentType", "DAMAGE_PHOTO")
            append("file", jpeg(3), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"x.jpg\"") })
        }) { bearerAuth(ivan) }.status)
    }

    @Test
    fun metadataStrippedButOrientationKept() = apiTest { http ->
        val s = http.anna()
        val claim = s.accidentDraft()
        val jpegFile = s.upload(claim.id, "DAMAGE_PHOTO", jpegWithExif(10)).body<ClaimFileDto>()
        val stored = http.get("/api/mobile/claims/${claim.id}/files/${jpegFile.id}/content") { bearerAuth(s.token) }.readRawBytes()
        assertFalse(contains(stored, "SECRET-GPS"), "геолокация из EXIF должна быть удалена")
        assertTrue(contains(stored, "Exif"), "ориентация снимка должна сохраниться")
        assertNotNull(ImageIO.read(stored.inputStream()))

        val pngFile = s.upload(claim.id, "DAMAGE_PHOTO", pngWithText(11), name = "p.png").body<ClaimFileDto>()
        val storedPng = http.get("/api/mobile/claims/${claim.id}/files/${pngFile.id}/content") { bearerAuth(s.token) }.readRawBytes()
        assertFalse(contains(storedPng, "SECRET-TEXT"))
        assertNotNull(ImageIO.read(storedPng.inputStream()))
    }

    @Test
    fun badFilesRejectedWithReasonAndHint_otherMaterialsKept() = apiTest { http ->
        val s = http.anna()
        val claim = s.accidentDraft()
        s.upload(claim.id, "DAMAGE_PHOTO", jpeg(20))

        suspend fun rejected(type: String, bytes: ByteArray, reason: String) {
            val response = s.upload(claim.id, type, bytes)
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, reason)
            val file = response.body<ClaimFileDto>()
            assertEquals(FileStatus.REJECTED, file.status)
            assertEquals(reason, file.rejectionReason?.name)
            assertNotNull(file.hint)
        }
        val good = jpeg(21)
        rejected("DAMAGE_PHOTO", good.copyOfRange(0, good.size / 2), "CORRUPTED")             // обрезанный
        rejected("DAMAGE_PHOTO", "это не картинка".toByteArray(), "UNSUPPORTED_FORMAT")        // текст с именем .jpg
        rejected("DAMAGE_PHOTO", pdf(), "UNSUPPORTED_FORMAT")                                  // PDF вместо фото
        rejected("DAMAGE_PHOTO", pngBomb(), "TOO_MANY_PIXELS")                                 // «бомба»
        rejected("ACCIDENT_NOTICE", pdf("/OpenAction << /S /JavaScript /JS (app.alert(1)) >>"), "ACTIVE_CONTENT")
        assertEquals(HttpStatusCode.Created, s.upload(claim.id, "ACCIDENT_NOTICE", pdf(), name = "notice.pdf").status)

        // принятый ранее файл на месте (вопрос 16)
        val details = http.get("/api/mobile/claims/${claim.id}") { bearerAuth(s.token) }.body<ClaimDetails>()
        assertEquals(2, details.files.count { it.status == FileStatus.ACCEPTED })
        assertEquals(5, details.files.count { it.status == FileStatus.REJECTED })
    }

    @Test
    fun rejectedFileCanBeReplaced() = apiTest { http ->
        val s = http.anna()
        val claim = s.accidentDraft()
        repeat(2) { s.upload(claim.id, "DAMAGE_PHOTO", jpeg(30 + it)) }
        s.upload(claim.id, "ACCIDENT_NOTICE", pdf(), name = "n.pdf")
        val broken = s.upload(claim.id, "DAMAGE_PHOTO", jpeg(33).copyOfRange(0, 200)).body<ClaimFileDto>()

        val before = http.get("/api/mobile/claims/${claim.id}/completeness") { bearerAuth(s.token) }.body<CompletenessDto>()
        assertEquals(listOf(broken.id), before.problemFiles.map { it.fileId })

        val replacement = s.upload(claim.id, "DAMAGE_PHOTO", jpeg(34), replaces = broken.id)
        assertEquals(HttpStatusCode.Created, replacement.status)
        assertEquals("REPLACED", query("select status from claim_files where id = ${broken.id}"))

        val after = http.get("/api/mobile/claims/${claim.id}/completeness") { bearerAuth(s.token) }.body<CompletenessDto>()
        assertTrue(after.complete, after.toString())
        // сквозной сценарий: все материалы загружены через API → отправка уходит в анализ
        assertEquals(ClaimStatus.IN_ANALYSIS, http.post("/api/mobile/claims/${claim.id}/submit") { bearerAuth(s.token) }.body<ClaimDetails>().status)

        // после отправки загружать нельзя, а оператор видит файлы
        assertEquals(HttpStatusCode.Conflict, s.upload(claim.id, "DAMAGE_PHOTO", jpeg(35)).status)
        val operator = http.loginOperator().accessToken
        val fileId = replacement.body<ClaimFileDto>().id
        assertEquals(HttpStatusCode.OK, http.get("/api/operator/claims/${claim.id}/files/$fileId/content") { bearerAuth(operator) }.status)
    }

    @Test
    fun duplicatesAndRetriesDoNotCreateCopies() = apiTest { http ->
        val s = http.anna()
        val claim = s.accidentDraft()
        val photo = jpeg(40)
        val first = s.upload(claim.id, "DAMAGE_PHOTO", photo).body<ClaimFileDto>()

        val again = s.upload(claim.id, "DAMAGE_PHOTO", photo, name = "copy.jpg")
        assertEquals(HttpStatusCode.OK, again.status)
        val duplicate = again.body<ClaimFileDto>()
        assertEquals(FileStatus.DUPLICATE, duplicate.status)
        assertEquals(first.id, duplicate.duplicateOfFileId)

        val requestId = UUID.randomUUID().toString()
        val a = s.upload(claim.id, "DAMAGE_PHOTO", jpeg(41), requestId = requestId)
        val b = s.upload(claim.id, "DAMAGE_PHOTO", jpeg(41), requestId = requestId)
        assertEquals(HttpStatusCode.Created, a.status)
        assertEquals(HttpStatusCode.OK, b.status)
        assertEquals(a.body<ClaimFileDto>().id, b.body<ClaimFileDto>().id)

        // то же фото в другом обращении — пометка для ручной проверки
        val other = s.accidentDraft()
        s.upload(other.id, "DAMAGE_PHOTO", photo)
        assertEquals(claim.id.toString(), query("select duplicate_of_claim_id from claims where id = ${other.id}"))
    }

    @Test
    fun limitsEnforced() = apiTest(TestDatabase.config("files.maxFilesPerClaim" to "2")) { http ->
        val s = http.anna()
        val claim = s.accidentDraft()
        repeat(2) { assertEquals(HttpStatusCode.Created, s.upload(claim.id, "DAMAGE_PHOTO", jpeg(50 + it)).status) }
        val third = s.upload(claim.id, "DAMAGE_PHOTO", jpeg(52))
        assertEquals(HttpStatusCode.Conflict, third.status)
        assertEquals("FILES_LIMIT", third.body<ErrorResponse>().error)

        TestDatabase.connection().use { it.createStatement().execute("update document_types set max_size_bytes = 100 where code = 'OTHER'") }
        try {
            val other = s.accidentDraft()
            val big = s.upload(other.id, "OTHER", pdf(" ".repeat(500)), name = "big.pdf")
            assertEquals(HttpStatusCode.PayloadTooLarge, big.status)
            assertEquals("0", query("select count(*) from claim_files where claim_id = ${other.id}"))
        } finally {
            TestDatabase.connection().use { it.createStatement().execute("update document_types set max_size_bytes = 20971520 where code = 'OTHER'") }
        }
    }

    @Test
    fun clientFileNameNeverReachesStoragePath() = apiTest { http ->
        val s = http.anna()
        val claim = s.accidentDraft()
        val file = s.upload(claim.id, "DAMAGE_PHOTO", jpeg(60), name = "../../../etc/passwd.jpg").body<ClaimFileDto>()
        assertEquals("passwd.jpg", file.originalName)
        val key = query("select storage_key from claim_files where id = ${file.id}")!!
        assertTrue(key.matches(Regex("""claims/${claim.id}/[0-9a-f-]{36}\.jpg""")), key)
        assertTrue(java.nio.file.Files.exists(TestDatabase.storageDir.resolve(key)))
    }

    // ---------------------------------------------------------------- после ревью: фото, отклонённые анализом, и похожие снимки

    @Test
    fun photoRejectedByAnalysisStaysViewable() = apiTest { http ->
        val s = http.anna()
        val claim = s.accidentDraft()
        val photo = s.upload(claim.id, "DAMAGE_PHOTO", jpeg(80)).body<ClaimFileDto>()
        val broken = s.upload(claim.id, "DAMAGE_PHOTO", jpeg(81).copyOfRange(0, 200)).body<ClaimFileDto>()
        // так фото пометит фоновый анализ (шаг 3): файл принят и сохранён, но снимок плохой
        TestDatabase.connection().use {
            it.createStatement().execute("update claim_files set status = 'REJECTED', rejection_reason = 'LOW_QUALITY' where id = ${photo.id}")
        }
        http.post("/api/mobile/claims/${claim.id}/submit") { bearerAuth(s.token) }
        val operator = http.loginOperator().accessToken

        // сам снимок видят и клиент (что переснять), и оператор (подтвердить или переопределить вердикт модели)
        assertEquals(HttpStatusCode.OK, http.get("/api/mobile/claims/${claim.id}/files/${photo.id}/content") { bearerAuth(s.token) }.status)
        assertEquals(HttpStatusCode.OK, http.get("/api/operator/claims/${claim.id}/files/${photo.id}/content") { bearerAuth(operator) }.status)
        // отклонённый при загрузке файл не хранится — отдавать нечего
        assertEquals(HttpStatusCode.NotFound, http.get("/api/mobile/claims/${claim.id}/files/${broken.id}/content") { bearerAuth(s.token) }.status)
        assertEquals(HttpStatusCode.NotFound, http.get("/api/operator/claims/${claim.id}/files/${broken.id}/content") { bearerAuth(operator) }.status)
    }

    @Test
    fun perceptualHashSurvivesResizeRecompressionAndRotation() {
        val original = scene(90)
        val hash = phash(original.encoded())!!
        // пересжатие и уменьшение (мессенджер) — тот же снимок
        assertTrue(PerceptualHash.distance(hash, phash(original.resized(320, 240).encoded())!!) <= 5)
        assertTrue(PerceptualHash.distance(hash, phash(original.encoded("png"))!!) <= 5)
        // EXIF-ориентация 6 против копии, где поворот уже применён к пикселям
        val withOrientation = phash(withExif(original.encoded()))!!
        val physicallyRotated = phash(original.rotatedClockwise().encoded())!!
        assertTrue(PerceptualHash.distance(withOrientation, physicallyRotated) <= 5)
        // другой снимок — далеко
        assertTrue(PerceptualHash.distance(hash, phash(scene(91).encoded())!!) > 10)
        // однотонный кадр не сравниваем: хэш у него случайный
        assertNull(phash(BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB).encoded()))
    }

    @Test
    fun resizedCopyOfPhotoFromAnotherClaimIsFlaggedForOperatorButNotShownToClient() = apiTest { http ->
        val anna = http.anna()
        val annaClaim = anna.accidentDraft()
        val original = scene(95)
        val annaFile = anna.upload(annaClaim.id, "DAMAGE_PHOTO", original.encoded()).body<ClaimFileDto>()

        // другой клиент загружает тот же снимок, пересжатый мессенджером: SHA-256 другой, снимок — тот же
        val oleg = http.client(DemoData.CLIENT_OLEG)
        val olegClaim = oleg.accidentDraft()
        val copy = oleg.upload(olegClaim.id, "DAMAGE_PHOTO", original.resized(480, 360).encoded())
        assertEquals(HttpStatusCode.Created, copy.status)
        val copyFile = copy.body<ClaimFileDto>()
        assertNull(copyFile.duplicateOfFileId, "клиенту не сообщаем, что снимок нашёлся в чужом обращении")
        assertEquals(annaClaim.id.toString(), query("select duplicate_of_claim_id from claims where id = ${olegClaim.id}"))

        http.post("/api/mobile/claims/${olegClaim.id}/submit") { bearerAuth(oleg.token) }
        val operator = http.loginOperator().accessToken
        val card = http.get("/api/operator/claims/${olegClaim.id}") { bearerAuth(operator) }.body<ClaimCard>()
        val flagged = card.files.single { it.file.id == copyFile.id }.foundInOtherClaim
        assertNotNull(flagged)
        assertEquals(annaClaim.id, flagged.claimId)
        assertEquals(annaFile.id, flagged.fileId)
        assertEquals(MatchType.SIMILAR, flagged.match)
    }

    @Test
    fun clientDeletesExtraFileOnlyFromDraft() = apiTest { http ->
        val s = http.anna()
        val claim = s.accidentDraft()
        val extra = s.upload(claim.id, "DAMAGE_PHOTO", jpeg(31)).body<ClaimFileDto>()
        val path = MobileApi.CLAIM_FILE.path("id" to claim.id, "fileId" to extra.id)
        val storageKey = query("select storage_key from claim_files where id = ${extra.id}")!!

        assertEquals(HttpStatusCode.NotFound, http.delete(path) { bearerAuth(http.client(DemoData.CLIENT_OLEG).token) }.status)
        assertEquals(HttpStatusCode.NoContent, http.delete(path) { bearerAuth(s.token) }.status)
        assertEquals(HttpStatusCode.NotFound, http.delete(path) { bearerAuth(s.token) }.status)

        val details = http.get("/api/mobile/claims/${claim.id}") { bearerAuth(s.token) }.body<ClaimDetails>()
        assertTrue(details.files.none { it.id == extra.id })
        assertEquals(HttpStatusCode.NotFound, http.get("$path/content") { bearerAuth(s.token) }.status)
        assertFalse(java.nio.file.Files.exists(TestDatabase.storageDir.resolve(storageKey)))
        assertEquals("DELETED", query("select status from claim_files where id = ${extra.id}"))
        assertEquals("1", query("select count(*) from audit_log where entity_id = ${extra.id} and action = 'FILE_DELETE'"))

        // после отправки файл не удаляется, а заменяется по запросу
        val kept = s.upload(claim.id, "DAMAGE_PHOTO", jpeg(32)).body<ClaimFileDto>()
        http.post("/api/mobile/claims/${claim.id}/submit") { bearerAuth(s.token) }
        val afterSubmit = http.delete(MobileApi.CLAIM_FILE.path("id" to claim.id, "fileId" to kept.id)) { bearerAuth(s.token) }
        assertEquals(HttpStatusCode.Conflict, afterSubmit.status)
    }
}
