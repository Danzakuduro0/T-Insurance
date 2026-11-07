package com.tinsurance.app.client

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tinsurance.app.ui.*
import ru.tinsurance.api.ClaimField
import ru.tinsurance.api.ClaimFileDto
import ru.tinsurance.api.FieldValueType
import ru.tinsurance.api.FileStatus
import ru.tinsurance.api.PolicyDto
import ru.tinsurance.api.mobile.ClaimDetails
import ru.tinsurance.api.mobile.FormDocumentDto
import tinsurance.shared.generated.resources.*

// Экраны 02–04 нового обращения на данных API. Демо-версии — в ClaimFormScreens.kt и MaterialScreens.kt.

@Composable
fun DraftContractScreen(
    draft: DraftState,
    policies: List<PolicyDto>,
    claimNumber: String?,
    onChange: ((ClaimForm) -> ClaimForm) -> Unit,
) {
    ScreenBody {
        PageTitle("Что случилось?")
        if (draft.eventTypes.isEmpty()) {
            if (draft.error != null) Notice(draft.error) else Caption("Загружаем виды событий…")
            return@ScreenBody
        }
        for (type in draft.eventTypes) {
            val selected = draft.form.eventType == type.code
            InsuranceCard(
                border = if (selected) InsuranceColors.Yellow else InsuranceColors.Line,
                padding = 16.dp,
                onClick = { onChange { it.copy(eventType = type.code) } },
            ) {
                InfoRow(
                    Res.drawable.car_small,
                    type.name,
                    "Нужно: " + type.documents.joinToString(", ") { "${it.name} (${it.minCount})" },
                    trailing = if (selected) Res.drawable.check_muted else null,
                )
            }
        }
        Text("Выберите договор", style = MaterialTheme.typography.titleMedium)
        if (policies.isEmpty()) Notice("Активных договоров нет — оформить обращение не получится.")
        for (policy in policies) {
            val selected = draft.form.policyId == policy.id
            InsuranceCard(
                border = if (selected) InsuranceColors.Yellow else InsuranceColors.Line,
                padding = 16.dp,
                onClick = { onChange { it.copy(policyId = policy.id) } },
            ) {
                InfoRow(
                    Res.drawable.shield_small,
                    policy.carName,
                    "${productName(policy.product)} · ${policy.policyNumber} · до " +
                        formatDate(policy.validTo, withYear = true),
                    trailing = if (selected) Res.drawable.check_muted else Res.drawable.chevron_small,
                )
            }
        }
        draft.error?.let { Notice(it) }
        Caption(
            if (claimNumber != null) "Черновик № $claimNumber сохранён"
            else "Черновик сохранится, когда вы нажмёте «Продолжить»"
        )
    }
}

@Composable
fun DraftEventScreen(draft: DraftState, onChange: ((ClaimForm) -> ClaimForm) -> Unit) {
    val form = draft.form
    val errors = draft.errors
    ScreenBody(spacing = 10.dp) {
        PageTitle("Расскажите о событии")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InsuranceField(
                "Дата события",
                form.date,
                { value -> onChange { it.copy(date = value) } },
                Modifier.weight(1f),
                error = errors["date"],
            )
            InsuranceField(
                "Время",
                form.time,
                { value -> onChange { it.copy(time = value) } },
                Modifier.weight(1f),
                keyboard = KeyboardType.Ascii,
                error = errors["time"],
            )
        }
        // Поля и порядок — из правил типа события (GET /event-types).
        for (field in draft.selectedType?.fields.orEmpty()) {
            when {
                field.code == ParticipantsField -> ParticipantsBlock(form, errors, onChange)
                field.valueType == FieldValueType.BOOLEAN ->
                    InsuranceCheckbox(field.title, form.flags[field.code] == true) { checked ->
                        onChange { it.copy(flags = it.flags + (field.code to checked)) }
                    }
                field.valueType == FieldValueType.STRING ->
                    InsuranceField(
                        if (field.required) field.title else "${field.title} (необязательно)",
                        form.text[field.code].orEmpty(),
                        { value -> onChange { it.copy(text = it.text + (field.code to value)) } },
                        multiline = field.code != ClaimField.CONTACT_PHONE.apiName &&
                            field.code != ClaimField.EVENT_LOCATION.apiName,
                        keyboard =
                            if (field.code == ClaimField.CONTACT_PHONE.apiName) KeyboardType.Phone
                            else KeyboardType.Text,
                        error = errors[field.code],
                    )
            }
        }
        draft.error?.let { Notice(it) }
        Caption("Данные сохранятся в черновике, когда вы перейдёте к материалам")
    }
}

@Composable
private fun ParticipantsBlock(
    form: ClaimForm,
    errors: Map<String, String>,
    onChange: ((ClaimForm) -> ClaimForm) -> Unit,
) {
    InsuranceCard(spacing = 10.dp) {
        InfoRow(Res.drawable.team_small, "Участники", "Вы указаны водителем-заявителем")
        InsuranceField(
            "ФИО второго водителя",
            form.otherDriver,
            { value -> onChange { it.copy(otherDriver = value) } },
            error = errors["otherDriver"],
        )
        InsuranceField(
            "Телефон второго водителя (необязательно)",
            form.otherPhone,
            { value -> onChange { it.copy(otherPhone = value) } },
            keyboard = KeyboardType.Phone,
        )
    }
}

@Composable
fun DraftMaterialsScreen(
    claim: ClaimDetails,
    documents: List<FormDocumentDto>,
    reply: ReplyState,
    onPickFiles: (FormDocumentDto) -> Unit,
    onTakePhoto: (FormDocumentDto) -> Unit,
    onDelete: (ClaimFileDto) -> Unit,
) {
    ScreenBody {
        PageTitle("Добавьте материалы")
        Caption("JPEG, PNG, PDF · до 20 МБ на файл. Фото проверяем сразу после загрузки.")
        for (doc in documents) {
            val files = claim.files.filter { it.documentType == doc.documentType && it.status.shownInDraft }
            val counted = files.count { it.status == FileStatus.ACCEPTED || it.status == FileStatus.UPLOADED }
            val enough = counted >= doc.minCount
            InsuranceCard(spacing = 10.dp) {
                InfoRow(
                    if (enough) Res.drawable.check_green else if (doc.isPhoto) Res.drawable.camera_amber
                    else Res.drawable.file_amber,
                    doc.name,
                    if (enough) "Добавлено достаточно · ${filesCount(counted)}"
                    else "Нужно ${doc.minCount}, добавлено $counted",
                    if (enough) InsuranceColors.Green else InsuranceColors.Amber,
                )
                for (file in files) DraftFileRow(file) { onDelete(file) }
                InsuranceButton(
                    "Выбрать файл",
                    { onPickFiles(doc) },
                    secondary = true,
                    enabled = !reply.uploading,
                )
                if (doc.isPhoto) TextLink("Сделать фото", { onTakePhoto(doc) }, centered = true)
            }
        }
        if (reply.uploading) Caption("Загружаем файлы…")
        reply.error?.let { Notice(it) }
    }
}

@Composable
private fun DraftFileRow(file: ClaimFileDto, onDelete: () -> Unit) {
    val (status, color) =
        when (file.status) {
            FileStatus.REJECTED -> "Не принят" to InsuranceColors.Red
            FileStatus.DUPLICATE -> "Уже загружен" to InsuranceColors.Muted
            FileStatus.UPLOADED -> "Проверяем качество" to InsuranceColors.Blue
            else -> "Принят" to InsuranceColors.Green
        }
    InsuranceCard(background = InsuranceColors.Field, border = null, padding = 10.dp, spacing = 4.dp) {
        InfoRow(
            if (file.status == FileStatus.REJECTED) Res.drawable.file_red else Res.drawable.file,
            file.originalName,
            status,
            color,
        )
        file.hint?.let { Caption(it, color = InsuranceColors.Red) }
        TextLink("Удалить", onDelete)
    }
}

/** В черновике показываем и отклонённые файлы — с подсказкой, что переснять. */
private val FileStatus.shownInDraft get() = this != FileStatus.DELETED && this != FileStatus.REPLACED

val FormDocumentDto.isPhoto get() = allowedMimeTypes.all { it.startsWith("image/") }
