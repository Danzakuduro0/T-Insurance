package com.tinsurance.app.client

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import ru.tinsurance.api.ClaimField
import ru.tinsurance.api.FieldKind
import ru.tinsurance.api.FieldValueType
import ru.tinsurance.api.ParticipantInput
import ru.tinsurance.api.mobile.ClaimDetails
import ru.tinsurance.api.mobile.EventTypeDto
import ru.tinsurance.api.mobile.UpdateClaimRequest

/**
 * Форма нового обращения (экраны 02–03). Поля, кроме даты и участников, строятся
 * по правилам выбранного типа события из GET /event-types.
 */
data class ClaimForm(
    val policyId: Long? = null,
    val eventType: String? = null,
    /** ДД.ММ.ГГГГ и ЧЧ:ММ — как вводит человек; в ISO переводим при сохранении. */
    val date: String = "",
    val time: String = "",
    /** Строковые поля: eventLocation, description, contactPhone и строковые ключи details. */
    val text: Map<String, String> = emptyMap(),
    /** Флажки details: policeCalled, europrotocol. */
    val flags: Map<String, Boolean> = emptyMap(),
    /** Второй участник ДТП; сам клиент добавляется водителем-заявителем автоматически. */
    val otherDriver: String = "",
    val otherPhone: String = "",
)

const val ParticipantsField = ClaimField.PARTICIPANTS

/** Поля формы для типа события, кроме участников — у них свой блок. */
fun EventTypeDto.formFields() = fields.filter { it.code != ParticipantsField }

val EventTypeDto.needsParticipants get() = fields.any { it.code == ParticipantsField }

/** Проверка до отправки на сервер: ключ — код поля, значение — текст ошибки. */
@OptIn(ExperimentalTime::class)
fun validateForm(form: ClaimForm, type: EventTypeDto?, now: kotlin.time.Instant = Clock.System.now()) =
    buildMap {
        if (!isValidDate(form.date)) put("date", "Укажите дату в формате ДД.ММ.ГГГГ")
        if (!Regex("([01][0-9]|2[0-3]):[0-5][0-9]").matches(form.time)) put("time", "Укажите время ЧЧ:ММ")
        val at = eventInstant(form)
        if (at != null && at > now) put("date", "Дата события не может быть в будущем")
        type?.formFields().orEmpty()
            .filter { it.required && it.valueType == FieldValueType.STRING }
            .forEach { if (form.text[it.code].isNullOrBlank()) put(it.code, "Заполните поле") }
        if (type?.needsParticipants == true && form.otherDriver.isBlank())
            put("otherDriver", "Укажите второго участника")
    }

@OptIn(ExperimentalTime::class)
private fun eventInstant(form: ClaimForm): kotlin.time.Instant? {
    val local = localDateTime(form) ?: return null
    return local.toInstant(TimeZone.currentSystemDefault())
}

private fun localDateTime(form: ClaimForm): LocalDateTime? {
    if (!isValidDate(form.date)) return null
    val (day, month, year) = form.date.split('.').map { it.toInt() }
    val (hour, minute) = form.time.split(':').map { it.toIntOrNull() ?: return null }
    return runCatching { LocalDateTime(year, month, day, hour, minute) }.getOrNull()
}

/** "16.10.2026" + "18:35" → "2026-10-16T18:35:00+07:00" в поясе устройства. */
@OptIn(ExperimentalTime::class)
fun eventAtIso(form: ClaimForm, zone: TimeZone = TimeZone.currentSystemDefault()): String? {
    val local = localDateTime(form) ?: return null
    val offset = zone.offsetAt(local.toInstant(zone))
    val pad = { n: Int -> n.toString().padStart(2, '0') }
    return "${local.year}-${pad(local.month.ordinal + 1)}-${pad(local.day)}" +
        "T${pad(local.hour)}:${pad(local.minute)}:00$offset"
}

/** Всё, что ввёл клиент на экране 03, одним PATCH. */
fun ClaimForm.toUpdate(type: EventTypeDto?, applicantName: String, applicantPhone: String?) =
    UpdateClaimRequest(
        policyId = policyId,
        eventType = eventType,
        eventAt = eventAtIso(this),
        eventLocation = text[ClaimField.EVENT_LOCATION.apiName],
        description = text[ClaimField.DESCRIPTION.apiName],
        contactPhone = text[ClaimField.CONTACT_PHONE.apiName],
        participants =
            if (type?.needsParticipants == true)
                listOfNotNull(
                    ParticipantInput("APPLICANT_DRIVER", applicantName, applicantPhone),
                    otherDriver.takeIf { it.isNotBlank() }?.let {
                        ParticipantInput("OTHER_DRIVER", it.trim(), otherPhone.trim().ifBlank { null })
                    },
                )
            else null,
        details = type?.let { detailsJson(it) },
    )

private fun ClaimForm.detailsJson(type: EventTypeDto): JsonObject? {
    val values =
        type.fields
            .filter { it.kind == FieldKind.DETAIL }
            .mapNotNull { field ->
                when (field.valueType) {
                    FieldValueType.BOOLEAN -> field.code to JsonPrimitive(flags[field.code] == true)
                    FieldValueType.STRING ->
                        text[field.code]?.takeIf { it.isNotBlank() }?.let { field.code to JsonPrimitive(it) }
                    else -> null
                }
            }
    return values.takeIf { it.isNotEmpty() }?.let { JsonObject(it.toMap()) }
}

/** Продолжение черновика: заполняем форму тем, что уже сохранено на сервере. */
fun ClaimDetails.toForm(defaultPhone: String?, zone: TimeZone = TimeZone.currentSystemDefault()): ClaimForm {
    val other = participants.firstOrNull { it.role == "OTHER_DRIVER" }
    // Сервер может вернуть время в UTC («…T07:20Z»): показываем местное время устройства.
    val local = eventAt?.let { localDateTimeOf(it, zone) }
    val pad = { n: Int -> n.toString().padStart(2, '0') }
    return ClaimForm(
        policyId = policyId,
        eventType = eventType,
        date = local?.let { "${pad(it.day)}.${pad(it.month.ordinal + 1)}.${it.year}" }.orEmpty(),
        time = local?.let { "${pad(it.hour)}:${pad(it.minute)}" }.orEmpty(),
        text =
            buildMap {
                eventLocation?.let { put(ClaimField.EVENT_LOCATION.apiName, it) }
                description?.let { put(ClaimField.DESCRIPTION.apiName, it) }
                (contactPhone ?: defaultPhone)?.let { put(ClaimField.CONTACT_PHONE.apiName, it) }
                details.forEach { (key, value) ->
                    if (value is JsonPrimitive && value.isString) put(key, value.content)
                }
            },
        flags =
            details.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull?.let { key to it }
            }.toMap(),
        otherDriver = other?.fullName.orEmpty(),
        otherPhone = other?.phone.orEmpty(),
    )
}
