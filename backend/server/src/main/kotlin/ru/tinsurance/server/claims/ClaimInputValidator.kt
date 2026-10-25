package ru.tinsurance.server.claims

import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.ResultRow
import ru.tinsurance.api.FieldKind
import ru.tinsurance.api.FieldValueType
import ru.tinsurance.api.ParticipantInput
import ru.tinsurance.server.common.ValidationException
import ru.tinsurance.server.db.Claims
import java.time.Duration
import java.time.OffsetDateTime

object ClaimInputValidator {
    private const val MAX_PARTICIPANTS = 20
    private val PARTICIPANT_ROLES = setOf("APPLICANT_DRIVER", "OTHER_DRIVER", "VICTIM", "WITNESS")
    /** Допуск на расхождение часов телефона и сервера. */
    private val EVENT_CLOCK_SKEW: Duration = Duration.ofMinutes(5)

    /** Размеры и участники — без БД, до транзакции. */
    fun validate(input: ClaimInput) {
        checkLength("eventLocation", input.eventLocation, 500)
        checkLength("description", input.description, 4000)
        checkLength("contactPhone", input.contactPhone, 32)
        input.participants?.let(::validateParticipants)
        input.details?.forEach { (key, value) ->
            if (value is JsonPrimitive && value.isString) checkLength(key, value.content, 2000)
        }
    }

    /** Полис клиента и тип события существуют. Внутри транзакции. */
    fun checkReferences(clientId: Long, input: ClaimInput) {
        input.policyId?.let { if (!PolicyRepository.isOwnedBy(it, clientId)) throw ValidationException("Полис не найден") }
        input.eventType?.let { if (!ClaimRepository.eventTypeExists(it)) throw ValidationException("Неизвестный тип события: $it") }
    }

    /** Только ключи, предусмотренные для типа события, и значения нужного вида. Внутри транзакции. */
    fun validateDetails(eventType: String?, details: JsonObject) {
        if (details.isEmpty()) return
        val type = eventType ?: throw ValidationException("Дополнительные сведения зависят от типа события — сначала укажите eventType")
        val rules = ClaimRules.fields(type).filter { it.kind == FieldKind.DETAIL }.associateBy { it.code }
        details.forEach { (key, value) ->
            val rule = rules[key] ?: throw ValidationException("Поле «$key» не предусмотрено для типа события $type")
            if (!matches(value, rule.valueType)) {
                throw ValidationException("Поле «${rule.title}»: ожидается значение типа ${rule.valueType}")
            }
        }
    }

    /** Дата события не в будущем и в периоде действия полиса. Внутри транзакции. */
    fun validateEventDate(claim: ResultRow) {
        val eventAt = claim[Claims.eventAt]!!
        if (eventAt.isAfter(OffsetDateTime.now().plus(EVENT_CLOCK_SKEW))) {
            throw ValidationException("Дата события не может быть в будущем")
        }
        val policy = PolicyRepository.get(claim[Claims.policyId]!!)
        val eventDate = eventAt.toLocalDate()
        if (eventDate < policy.validFrom || eventDate > policy.validTo) {
            throw ValidationException(
                "Дата события вне периода действия полиса ${policy.number} (${policy.validFrom} — ${policy.validTo})"
            )
        }
    }

    private fun validateParticipants(list: List<ParticipantInput>) {
        if (list.size > MAX_PARTICIPANTS) throw ValidationException("Участников не больше $MAX_PARTICIPANTS")
        list.forEach { p ->
            checkLength("participants.fullName", p.fullName, 200)
            checkLength("participants.phone", p.phone, 32)
            checkLength("participants.insurer", p.insurer, 200)
            checkLength("participants.note", p.note, 1000)
            checkLength("participants.vehicle", p.vehicle?.toString(), 2000)
        }
        list.forEach { p ->
            if (p.role !in PARTICIPANT_ROLES) throw ValidationException("Роль участника: одна из ${PARTICIPANT_ROLES.joinToString()}")
            if (p.fullName.isBlank()) throw ValidationException("Укажите имя участника")
        }
    }

    private fun matches(value: JsonElement, type: FieldValueType): Boolean = value is JsonNull || when (type) {
        FieldValueType.BOOLEAN -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        FieldValueType.NUMBER -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        FieldValueType.STRING -> value is JsonPrimitive && value.isString
        FieldValueType.LIST -> true
    }

    private fun checkLength(field: String, value: String?, max: Int) {
        if (value != null && value.length > max) throw ValidationException("Поле «$field»: не длиннее $max символов")
    }
}
