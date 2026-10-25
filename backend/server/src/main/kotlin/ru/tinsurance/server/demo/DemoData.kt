package ru.tinsurance.server.demo

import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.ClaimStatus.*
import ru.tinsurance.server.auth.PasswordHasher
import ru.tinsurance.server.claims.Actor
import ru.tinsurance.api.*
import ru.tinsurance.server.claims.ClaimStatusService
import ru.tinsurance.server.db.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Вымышленные данные для разработки и показа. Заполняются один раз, если таблица users пуста.
 *
 * Когда создаются (см. [DemoSettings]):
 *   dev  — по умолчанию да, пароль всех учёток — DEV_DEMO_PASSWORD (только для локального запуска);
 *   prod — только при DEMO_SEED=true и с паролем из DEMO_PASSWORD (не короче 10 символов, не dev-пароль).
 *
 * Тестовые учётные записи:
 *   клиенты (мобильное приложение): +79000000001, +79000000002, +79000000003
 *   сотрудники (веб):               operator1@tstrah.test, operator2@tstrah.test, admin@tstrah.test
 */
object DemoData {
    /** Пароль демо-учёток в режиме dev. В prod не принимается. */
    const val DEV_DEMO_PASSWORD = "demo12345"

    const val CLIENT_IVAN = "+79000000001"
    const val CLIENT_ANNA = "+79000000002"
    const val CLIENT_OLEG = "+79000000003"
    const val OPERATOR_1 = "operator1@tstrah.test"
    const val OPERATOR_2 = "operator2@tstrah.test"
    const val ADMIN = "admin@tstrah.test"

    private val log = LoggerFactory.getLogger(DemoData::class.java)

    fun seedIfEmpty(db: Database, password: String) = transaction(db) {
        if (Users.selectAll().limit(1).any()) return@transaction
        Seeder(PasswordHasher.hash(password)).run()
        log.info("Demo data created: 3 clients, 3 staff, 6 claims")
    }

    private class Seeder(private val passwordHash: String) {
        private val now = OffsetDateTime.now()
        private var fileCounter = 0

        fun run() {
            val ivan = client("Иван Петров", CLIENT_IVAN)
            val anna = client("Анна Смирнова", CLIENT_ANNA)
            val oleg = client("Олег Кузнецов", CLIENT_OLEG)
            val op1 = staff(Role.OPERATOR, "Мария Иванова", OPERATOR_1, listOf("STANDARD"))
            val op2 = staff(Role.OPERATOR, "Дмитрий Соколов", OPERATOR_2, listOf("STANDARD", "MANUAL_REVIEW"))
            staff(Role.ADMIN, "Администратор", ADMIN, emptyList())

            val ivanPolicy = policy(ivan, "KASKO-2026-000101", "KASKO", "Lada", "Vesta", 2021)
            val annaPolicy = policy(anna, "KASKO-2026-000102", "KASKO", "Kia", "Rio", 2019)
            val olegPolicy = policy(oleg, "OSAGO-2026-000103", "OSAGO", "Hyundai", "Solaris", 2020)

            // 1. Черновик — клиент начал заполнять и не отправил
            claim(ivan, ivanPolicy, "CAR_ACCIDENT", daysAgo = 0, location = null,
                description = "Задели во дворе, заполню позже", path = listOf())

            // 2. Неполный комплект: система вернула клиенту на дозагрузку
            claim(ivan, ivanPolicy, "CAR_ACCIDENT", daysAgo = 2, location = "Новосибирск, ул. Ленина, 10",
                description = "Столкновение на перекрёстке, повреждён передний бампер",
                path = listOf(SUBMITTED, NEEDS_INFO),
                files = listOf("DAMAGE_PHOTO" to 3, "DRIVER_LICENSE" to 1),
                missing = listOf("ACCIDENT_NOTICE"),
                circumstances = "Второй автомобиль не уступил дорогу при повороте налево")

            // 3. Простой случай: стекло, высокая уверенность модели, в очереди «Стёкла»
            claim(anna, annaPolicy, "CAR_GLASS", daysAgo = 1, location = "Трасса Р-254, 15 км",
                description = "Камень из-под колеса, трещина на лобовом стекле",
                path = listOf(SUBMITTED, IN_ANALYSIS, QUEUED),
                files = listOf("DAMAGE_PHOTO" to 2),
                routing = Routing("STANDARD", priority = 1, damage = "BROKEN_GLASS", confidence = 0.93f))

            // 4. Сомнительный: низкая уверенность модели, ручная проверка, SLA просрочен
            claim(anna, annaPolicy, "CAR_ACCIDENT", daysAgo = 3, location = "Новосибирск, парковка ТЦ",
                description = "Обнаружила повреждение после парковки, второго участника нет",
                path = listOf(SUBMITTED, IN_ANALYSIS, QUEUED),
                files = listOf("DAMAGE_PHOTO" to 3, "ACCIDENT_NOTICE" to 1),
                routing = Routing("MANUAL_REVIEW", priority = 3, damage = "DENT", confidence = 0.48f, manual = true),
                circumstances = "Повреждение обнаружено после парковки, виновник неизвестен")

            // 5. В работе у оператора; оператор исправил направление системы
            claim(oleg, olegPolicy, "CAR_ACCIDENT", daysAgo = 4, location = "Новосибирск, Красный проспект",
                description = "ДТП с двумя участниками, повреждены дверь и крыло",
                path = listOf(SUBMITTED, IN_ANALYSIS, QUEUED, ASSIGNED, IN_REVIEW),
                files = listOf("DAMAGE_PHOTO" to 4, "ACCIDENT_NOTICE" to 1),
                routing = Routing("STANDARD", priority = 1, damage = "DENT", confidence = 0.71f,
                    priorityOverride = 2 to "Два участника и повреждения нескольких деталей"),
                circumstances = "Столкновение при перестроении, второй участник признал вину",
                operator = op2,
                participant = true,
                internalNote = "Запросить у второго участника номер полиса ОСАГО")

            // 6. Рассмотрение завершено
            claim(oleg, olegPolicy, "CAR_DAMAGE", daysAgo = 10, location = "Новосибирск, ул. Гоголя, 5",
                description = "Повреждение крыши упавшей веткой",
                path = listOf(SUBMITTED, IN_ANALYSIS, QUEUED, ASSIGNED, IN_REVIEW, COMPLETED),
                files = listOf("DAMAGE_PHOTO" to 3),
                routing = Routing("STANDARD", priority = 1, damage = "DENT", confidence = 0.88f),
                circumstances = "Ветка упала на крышу во время сильного ветра",
                operator = op1)
        }

        private fun client(name: String, phone: String) = Users.insert {
            it[role] = Role.CLIENT
            it[fullName] = name
            it[Users.phone] = phone
            it[passwordHash] = this@Seeder.passwordHash
            it[isActive] = true
            it[createdAt] = now.minusDays(30)
        }[Users.id]

        private fun staff(role: Role, name: String, email: String, directions: List<String>): Long {
            val id = Users.insert {
                it[Users.role] = role
                it[fullName] = name
                it[Users.email] = email
                it[passwordHash] = this@Seeder.passwordHash
                it[maxActiveClaims] = if (role == Role.OPERATOR) 10 else null
                it[availability] = OperatorAvailability.ONLINE
                it[isActive] = true
                it[createdAt] = now.minusDays(30)
            }[Users.id]
            directions.forEach { code ->
                OperatorDirections.insert {
                    it[operatorId] = id
                    it[directionCode] = code
                }
            }
            return id
        }

        private fun policy(clientId: Long, number: String, product: String, make: String, model: String, year: Int) =
            Policies.insert {
                it[Policies.clientId] = clientId
                it[policyNumber] = number
                it[Policies.product] = product
                it[insuredObject] = buildJsonObject {
                    put("make", make)
                    put("model", model)
                    put("year", year)
                    put("vin", "XTA0000000DEMO${clientId.toString().padStart(3, '0')}")
                }
                it[validFrom] = LocalDate.now().minusMonths(6)
                it[validTo] = LocalDate.now().plusMonths(6)
                it[createdAt] = now.minusDays(30)
            }[Policies.id]

        data class Routing(
            val direction: String,
            val priority: Int,
            val damage: String,
            val confidence: Float,
            val manual: Boolean = false,
            /** исправление приоритета оператором: новое значение и причина */
            val priorityOverride: Pair<Int, String>? = null,
        )

        @Suppress("LongParameterList")
        private fun claim(
            clientId: Long,
            policyId: Long,
            eventType: String,
            daysAgo: Long,
            location: String?,
            description: String,
            path: List<ClaimStatus>,
            files: List<Pair<String, Int>> = emptyList(),
            missing: List<String> = emptyList(),
            routing: Routing? = null,
            operator: Long? = null,
            participant: Boolean = false,
            internalNote: String? = null,
            circumstances: String? = null,
        ) {
            val created = now.minusDays(daysAgo).minusHours(2)
            var at = created
            fun next(): OffsetDateTime { at = at.plusMinutes(20); return at }

            val claimId = Claims.insert {
                it[Claims.clientId] = clientId
                it[Claims.policyId] = policyId
                it[Claims.eventType] = eventType
                it[eventAt] = created.minusHours(3)
                it[eventLocation] = location
                it[Claims.description] = description
                it[contactPhone] = Users.select(Users.phone).where { Users.id eq clientId }.single()[Users.phone]
                it[eventDetails] = buildJsonObject { circumstances?.let { c -> put("circumstances", c) } }
                it[status] = DRAFT
                it[statusChangedAt] = created
                it[requiresManualReview] = false
                it[version] = 0
                it[createdAt] = created
                it[updatedAt] = created
            }[Claims.id]
            ClaimStatusService.recordCreated(claimId, Actor.client(clientId), created)

            files.forEach { (type, count) -> repeat(count) { file(claimId, clientId, type, created) } }

            if (eventType == "CAR_ACCIDENT") {
                ClaimParticipants.insert {
                    it[ClaimParticipants.claimId] = claimId
                    it[role] = "APPLICANT_DRIVER"
                    it[fullName] = Users.select(Users.fullName).where { Users.id eq clientId }.single()[Users.fullName]
                }
            }
            if (participant) {
                ClaimParticipants.insert {
                    it[ClaimParticipants.claimId] = claimId
                    it[role] = "OTHER_DRIVER"
                    it[fullName] = "Сергей Волков"
                    it[phone] = "+79000000099"
                    it[vehicle] = buildJsonObject { put("make", "Toyota"); put("model", "Camry") }
                    it[insurer] = "Вымышленное страхование"
                }
            }

            var current = DRAFT
            for (to in path) {
                val t = next()
                val actor = when (to) {
                    SUBMITTED -> Actor.client(clientId)
                    ASSIGNED, IN_REVIEW, COMPLETED -> Actor(ActorType.OPERATOR, operator)
                    else -> Actor.SYSTEM
                }
                if (to == ASSIGNED) {
                    Claims.update({ Claims.id eq claimId }) { it[assignedOperatorId] = operator }
                }
                if (to == QUEUED && routing != null) applyRouting(claimId, routing, t)
                if (to == NEEDS_INFO) requestMissing(claimId, missing, t)

                val reason = when (to) {
                    NEEDS_INFO -> "Не хватает документов"
                    COMPLETED -> "Материалы проверены, обращение передано на урегулирование"
                    else -> null
                }
                val clientComment = when (to) {
                    NEEDS_INFO -> reason
                    COMPLETED -> "Рассмотрение завершено, обращение передано на урегулирование"
                    else -> null
                }
                ClaimStatusService.transition(claimId, current, to, actor, reason, clientComment = clientComment, at = t)
                current = to
            }

            routing?.priorityOverride?.let { (priority, reason) ->
                overridePriority(claimId, priority, reason, operator!!, next())
            }
            internalNote?.let { note ->
                ClaimComments.insert {
                    it[ClaimComments.claimId] = claimId
                    it[authorId] = operator!!
                    it[visibility] = CommentVisibility.INTERNAL
                    it[body] = note
                    it[createdAt] = next()
                }
            }
            Claims.update({ Claims.id eq claimId }) { it[updatedAt] = at }
        }

        private fun file(claimId: Long, clientId: Long, type: String, created: OffsetDateTime) {
            val n = ++fileCounter
            val isPdf = type != "DAMAGE_PHOTO"
            ClaimFiles.insert {
                it[ClaimFiles.claimId] = claimId
                it[documentType] = type
                it[storageKey] = "demo/claim-$claimId/file-$n.${if (isPdf) "pdf" else "jpg"}"
                it[originalName] = if (isPdf) "${type.lowercase()}.pdf" else "IMG_$n.jpg"
                it[mimeType] = if (isPdf) "application/pdf" else "image/jpeg"
                it[sizeBytes] = 250_000L + n * 1_000
                it[sha256] = "demo".padEnd(60, '0') + n.toString().padStart(4, '0')
                it[status] = FileStatus.ACCEPTED
                it[uploadedBy] = clientId
                it[uploadedAt] = created.plusMinutes(5)
            }
        }

        private fun requestMissing(claimId: Long, missing: List<String>, at: OffsetDateTime) {
            val requestId = InfoRequests.insert {
                it[InfoRequests.claimId] = claimId
                it[requestSource] = ActorType.SYSTEM
                it[message] = "Для рассмотрения обращения загрузите недостающие документы"
                it[status] = InfoRequestStatus.OPEN
                it[dueAt] = at.plusDays(7)
                it[createdAt] = at
            }[InfoRequests.id]
            missing.forEach { type ->
                InfoRequestItems.insert {
                    it[InfoRequestItems.requestId] = requestId
                    it[documentType] = type
                    it[minCount] = 1
                    it[isFulfilled] = false
                }
            }
            decision(claimId, DecisionKind.COMPLETENESS, buildJsonObject {
                put("complete", false)
                putJsonArray("missingDocuments") { missing.forEach { add(buildJsonObject { put("documentType", it) }) } }
            }, null, "Не хватает: ${missing.joinToString()}", at)
        }

        private fun applyRouting(claimId: Long, r: Routing, at: OffsetDateTime) {
            decision(claimId, DecisionKind.COMPLETENESS, buildJsonObject { put("complete", true) }, null, "Комплект полный", at)
            decision(claimId, DecisionKind.DAMAGE, buildJsonObject { put("damageType", r.damage) }, r.confidence,
                "Модель: ${r.damage} (${(r.confidence * 100).toInt()}%)", at)
            decision(claimId, DecisionKind.ROUTING, buildJsonObject { put("direction", r.direction) }, r.confidence,
                if (r.manual) "Уверенность модели ниже порога 0.6 — ручная проверка" else "Тип события и вид повреждения", at)
            decision(claimId, DecisionKind.PRIORITY, buildJsonObject { put("priority", r.priority) }, null, "Правила срочности", at)
            Claims.update({ Claims.id eq claimId }) {
                it[directionCode] = r.direction
                it[priority] = r.priority.toShort()
                it[requiresManualReview] = r.manual
            }
        }

        private fun overridePriority(claimId: Long, priority: Int, reason: String, operatorId: Long, at: OffsetDateTime) {
            val original = ClaimDecisions.selectAll()
                .where { (ClaimDecisions.claimId eq claimId) and (ClaimDecisions.kind eq DecisionKind.PRIORITY) and (ClaimDecisions.isCurrent eq true) }
                .single()[ClaimDecisions.id]
            ClaimDecisions.update({ ClaimDecisions.id eq original }) { it[isCurrent] = false }
            ClaimDecisions.insert {
                it[ClaimDecisions.claimId] = claimId
                it[kind] = DecisionKind.PRIORITY
                it[decisionSource] = DecisionSource.OPERATOR
                it[action] = DecisionAction.OVERRIDE
                it[value] = buildJsonObject { put("priority", priority) }
                it[ClaimDecisions.reason] = reason
                it[supersedesId] = original
                it[isCurrent] = true
                it[createdBy] = operatorId
                it[createdAt] = at
            }
            Claims.update({ Claims.id eq claimId }) { it[Claims.priority] = priority.toShort() }
        }

        private fun decision(claimId: Long, kind: DecisionKind, value: JsonObject, confidence: Float?, reason: String, at: OffsetDateTime) {
            ClaimDecisions.insert {
                it[ClaimDecisions.claimId] = claimId
                it[ClaimDecisions.kind] = kind
                it[decisionSource] = DecisionSource.SYSTEM
                it[ClaimDecisions.value] = value
                it[ClaimDecisions.confidence] = confidence
                it[ClaimDecisions.reason] = reason
                it[ruleVersion] = "demo-1"
                it[isCurrent] = true
                it[createdAt] = at
            }
        }
    }
}
