package com.tinsurance.app

import com.tinsurance.app.client.ClaimForm
import com.tinsurance.app.client.eventAtIso
import com.tinsurance.app.client.toForm
import com.tinsurance.app.client.toUpdate
import com.tinsurance.app.client.validateForm
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.tinsurance.api.FieldKind
import ru.tinsurance.api.FieldValueType
import ru.tinsurance.api.ParticipantDto
import ru.tinsurance.api.mobile.ClaimDetails
import ru.tinsurance.api.mobile.EventTypeDto
import ru.tinsurance.api.mobile.FormFieldDto

@OptIn(ExperimentalTime::class)
class ClaimFormTest {
    private fun field(code: String, kind: FieldKind, type: FieldValueType, required: Boolean = true) =
        FormFieldDto(code, code, kind, type, required)

    // Как в справочнике backend для ДТП (V4__align_with_answers.sql).
    private val accident =
        EventTypeDto(
            "CAR_ACCIDENT",
            "ДТП",
            listOf(
                field("eventLocation", FieldKind.CORE, FieldValueType.STRING),
                field("description", FieldKind.CORE, FieldValueType.STRING),
                field("contactPhone", FieldKind.CORE, FieldValueType.STRING),
                field("participants", FieldKind.CORE, FieldValueType.LIST),
                field("circumstances", FieldKind.DETAIL, FieldValueType.STRING),
                field("policeCalled", FieldKind.DETAIL, FieldValueType.BOOLEAN, required = false),
            ),
            emptyList(),
        )

    private val filled =
        ClaimForm(
            policyId = 1,
            eventType = "CAR_ACCIDENT",
            date = "16.10.2026",
            time = "18:35",
            text =
                mapOf(
                    "eventLocation" to "Томск",
                    "description" to "Задели дверь",
                    "contactPhone" to "+79000000003",
                    "circumstances" to "Перестроение",
                ),
            flags = mapOf("policeCalled" to true),
            otherDriver = "Пётр Иванов",
        )

    private val later = Instant.parse("2026-11-05T00:00:00Z")

    @Test
    fun eventAtKeepsLocalTimeWithZoneOffset() {
        assertEquals("2026-10-16T18:35:00+07:00", eventAtIso(filled, TimeZone.of("Asia/Novosibirsk")))
        assertEquals("2026-10-16T18:35:00Z", eventAtIso(filled, TimeZone.UTC))
        assertNull(eventAtIso(filled.copy(date = "31.02.2026")))
    }

    @Test
    fun filledFormHasNoErrors() {
        assertTrue(validateForm(filled, accident, later).isEmpty())
    }

    @Test
    fun requiredFieldsParticipantsAndFutureDateAreChecked() {
        val errors =
            validateForm(
                filled.copy(text = filled.text - "circumstances", otherDriver = " ", date = "05.11.2027"),
                accident,
                later,
            )
        assertEquals(setOf("circumstances", "otherDriver", "date"), errors.keys)
        assertEquals("Дата события не может быть в будущем", errors["date"])
    }

    @Test
    fun updateCarriesParticipantsAndTypedDetails() {
        val update = filled.toUpdate(accident, "Олег Кузнецов", "+79000000003")
        assertEquals("Задели дверь", update.description)
        assertEquals(listOf("APPLICANT_DRIVER", "OTHER_DRIVER"), update.participants?.map { it.role })
        assertEquals("Пётр Иванов", update.participants?.last()?.fullName)
        assertEquals(JsonPrimitive("Перестроение"), update.details?.get("circumstances"))
        assertEquals(JsonPrimitive(true), update.details?.get("policeCalled"))
        // Основные поля не дублируются в details.
        assertNull(update.details?.get("description"))
        assertNotNull(update.eventAt)
    }

    @Test
    fun savedDraftFillsForm() {
        val draft =
            ClaimDetails(
                id = 7, claimNumber = "TS-0000007", status = ru.tinsurance.api.ClaimStatus.DRAFT,
                statusTitle = "Черновик", isFinal = false, policyId = 1, policyNumber = "KASKO-1",
                eventType = "CAR_ACCIDENT", eventTypeName = "ДТП", eventAt = "2026-10-16T18:35:00+07:00",
                eventLocation = "Томск", description = null, contactPhone = null,
                participants = listOf(ParticipantDto(1, "OTHER_DRIVER", "Пётр Иванов", "+79001112233", null, null, null)),
                details = JsonObject(mapOf("circumstances" to JsonPrimitive("Перестроение"), "policeCalled" to JsonPrimitive(false))),
                createdAt = "2026-10-16T19:00:00+07:00", updatedAt = "2026-10-16T19:00:00+07:00",
                submittedAt = null, openInfoRequest = null, files = emptyList(),
            )
        val form = draft.toForm(defaultPhone = "+79000000003", zone = TimeZone.of("Asia/Novosibirsk"))
        assertEquals("16.10.2026", form.date)
        assertEquals("18:35", form.time)
        assertEquals("Томск", form.text["eventLocation"])
        assertEquals("+79000000003", form.text["contactPhone"])
        assertEquals("Перестроение", form.text["circumstances"])
        assertEquals(false, form.flags["policeCalled"])
        assertEquals("Пётр Иванов", form.otherDriver)
        assertEquals("+79001112233", form.otherPhone)
    }
}
