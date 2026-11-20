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
import ru.tinsurance.api.mobile.FormFieldDto
import tinsurance.shared.generated.resources.*

// Экраны 02–04 нового обращения на данных API. Демо-версии — в ClaimFormScreens.kt и MaterialScreens.kt.

@Composable
fun DraftContractScreen(
    draft: DraftState,
    policies: List<PolicyDto>,
    claimNumber: String?,
    onChange: ((ClaimForm) -> ClaimForm) -> Unit,
) {
    // От 481 px карточки событий и договоров идут по две в ряд (ТЗ 5.2).
    val twoColumns = LocalWindowWidth.current.atLeastMedium
    ScreenBody {
        PageTitle("Что случилось?")
        if (draft.eventTypes.isEmpty()) {
            if (draft.error != null) Notice(draft.error) else Caption("Загружаем виды событий…")
            return@ScreenBody
        }
        CardGrid(draft.eventTypes, twoColumns) { type, cardModifier ->
            val selected = draft.form.eventType == type.code
            InsuranceCard(
                cardModifier,
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
        CardGrid(policies, twoColumns) { policy, cardModifier ->
            val selected = draft.form.policyId == policy.id
            InsuranceCard(
                cardModifier,
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

/**
 * Карточки в одну колонку или, на планшете, по две в ряд. Карточки в ряду одной высоты,
 * чтобы рамки выбора стояли ровно; нечётная последняя занимает половину ширины.
 */
@Composable
private fun <T> CardGrid(
    items: List<T>,
    twoColumns: Boolean,
    card: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    if (!twoColumns) {
        for (item in items) card(item, Modifier.fillMaxWidth())
        return
    }
    for (row in items.chunked(2)) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (item in row) card(item, Modifier.weight(1f).fillMaxHeight())
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
fun DraftEventScreen(draft: DraftState, onChange: ((ClaimForm) -> ClaimForm) -> Unit) {
    val form = draft.form
    val errors = draft.errors
    // От 481 px форма в две колонки (ТЗ 5.2): место рядом с телефоном, ФИО рядом с телефоном участника.
    val twoColumns = LocalWindowWidth.current.atLeastMedium
    val fields = draft.selectedType?.fields.orEmpty()
    val phoneField = fields.find { it.code == ClaimField.CONTACT_PHONE.apiName }
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
        for (field in fields) {
            when {
                // В две колонки телефон уже стоит рядом с местом события.
                twoColumns && field == phoneField && fields.any { it.code == ClaimField.EVENT_LOCATION.apiName } ->
                    Unit
                twoColumns && field.code == ClaimField.EVENT_LOCATION.apiName && phoneField != null ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextFormField(field, form, errors, onChange, Modifier.weight(1f))
                        TextFormField(phoneField, form, errors, onChange, Modifier.weight(1f))
                    }
                field.code == ParticipantsField -> ParticipantsBlock(form, errors, onChange, twoColumns)
                field.valueType == FieldValueType.BOOLEAN ->
                    InsuranceCheckbox(field.title, form.flags[field.code] == true) { checked ->
                        onChange { it.copy(flags = it.flags + (field.code to checked)) }
                    }
                field.valueType == FieldValueType.STRING -> TextFormField(field, form, errors, onChange)
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
    twoColumns: Boolean,
) {
    InsuranceCard(spacing = 10.dp) {
        InfoRow(Res.drawable.team_small, "Участники", "Вы указаны водителем-заявителем")
        @Composable
        fun name(modifier: Modifier) =
            InsuranceField(
                "ФИО второго водителя",
                form.otherDriver,
                { value -> onChange { it.copy(otherDriver = value) } },
                modifier,
                error = errors["otherDriver"],
            )
        @Composable
        fun phone(modifier: Modifier) =
            InsuranceField(
                "Телефон второго водителя (необязательно)",
                form.otherPhone,
                { value -> onChange { it.copy(otherPhone = value) } },
                modifier,
                keyboard = KeyboardType.Phone,
            )
        if (twoColumns)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                name(Modifier.weight(1f))
                phone(Modifier.weight(1f))
            }
        else {
            name(Modifier)
            phone(Modifier)
        }
    }
}

/** Строковое поле формы по правилу типа события: подпись, клавиатура, многострочность. */
@Composable
private fun TextFormField(
    field: FormFieldDto,
    form: ClaimForm,
    errors: Map<String, String>,
    onChange: ((ClaimForm) -> ClaimForm) -> Unit,
    modifier: Modifier = Modifier,
) {
    InsuranceField(
        if (field.required) field.title else "${field.title} (необязательно)",
        form.text[field.code].orEmpty(),
        { value -> onChange { it.copy(text = it.text + (field.code to value)) } },
        modifier,
        multiline = field.code != ClaimField.CONTACT_PHONE.apiName &&
            field.code != ClaimField.EVENT_LOCATION.apiName,
        keyboard =
            if (field.code == ClaimField.CONTACT_PHONE.apiName) KeyboardType.Phone else KeyboardType.Text,
        error = errors[field.code],
    )
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
