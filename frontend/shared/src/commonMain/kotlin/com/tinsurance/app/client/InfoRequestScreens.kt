package com.tinsurance.app.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tinsurance.app.ui.*
import kotlinx.serialization.json.JsonPrimitive
import ru.tinsurance.api.ClaimField
import ru.tinsurance.api.ClaimFileDto
import ru.tinsurance.api.FileStatus
import ru.tinsurance.api.InfoRequestItemDto
import ru.tinsurance.api.MaterialIssue
import ru.tinsurance.api.mobile.ClaimDetails
import tinsurance.shared.generated.resources.*

// Экраны 09 и 10 на данных openInfoRequest из GET /claims/{id}. Демо-версии — в ClaimStatusScreens.kt
// и MaterialScreens.kt.

@Composable
fun ClaimRequestScreen(state: ClaimState, onRetry: () -> Unit) {
    ScreenBody {
        val claim = state.details
        if (claim == null) {
            LoadingOrError(state, onRetry)
            return@ScreenBody
        }
        val request = claim.openInfoRequest
        if (request == null) {
            Notice("Открытых запросов нет. ${statusHint(claim.status)}.")
        } else {
            StatusBadge("Нужно уточнение", InsuranceColors.AmberSoft, InsuranceColors.Amber)
            InsuranceCard(padding = 16.dp, spacing = 12.dp) {
                Text(requestTitle(request.items.size), style = MaterialTheme.typography.titleMedium)
                Caption("Запрос специалиста · ${formatDateTime(request.createdAt)}")
                Text(
                    request.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = InsuranceColors.Muted,
                )
                request.dueAt?.let { Caption("Ответьте до ${formatDate(it, withYear = true)}") }
                request.items.forEachIndexed { index, item ->
                    if (index > 0)
                        Box(Modifier.fillMaxWidth().height(1.dp).background(InsuranceColors.Line))
                    InfoRow(
                        itemIcon(item),
                        itemTitle(item, claim),
                        if (item.isFulfilled) "Готово" else itemSubtitle(item),
                        if (item.isFulfilled) InsuranceColors.Green else InsuranceColors.Amber,
                    )
                }
            }
        }
        InsuranceCard(spacing = 10.dp) {
            Label("История обращения")
            Timeline(timelineItems(claim, state.timeline))
        }
    }
}

@Composable
fun ClaimReplyScreen(
    state: ClaimState,
    reply: ReplyState,
    fields: Map<String, String>,
    onField: (String, String) -> Unit,
    onPickFiles: (InfoRequestItemDto) -> Unit,
    onTakePhoto: (InfoRequestItemDto) -> Unit,
    onRequest: () -> Unit,
    onRetry: () -> Unit,
) {
    ScreenBody {
        val claim = state.details
        val request = claim?.openInfoRequest
        if (claim == null || request == null) {
            if (claim == null) LoadingOrError(state, onRetry)
            else Notice("Открытых запросов нет — дополнять нечего.")
            return@ScreenBody
        }
        Caption("К обращению № ${claim.claimNumber}")
        TextLink(
            "Запрос от ${formatDateTime(request.createdAt)}",
            onRequest,
            Res.drawable.file_link,
        )
        for (item in request.items) {
            val title = itemTitle(item, claim)
            when {
                item.isFulfilled ->
                    InfoRow(Res.drawable.check_green, title, "Готово", InsuranceColors.Green)
                item.fieldName != null && !isTextField(item.fieldName!!) -> {
                    Label(title)
                    Caption("Это поле в приложении пока изменить нельзя")
                }
                item.fieldName != null ->
                    InsuranceField(
                        title,
                        fields[item.fieldName].orEmpty(),
                        { onField(item.fieldName!!, it) },
                        multiline = item.fieldName == ClaimField.DESCRIPTION.apiName,
                    )
                else -> {
                    Label(title)
                    Caption(itemSubtitle(item))
                    InsuranceButton(
                        if (item.fileId != null) "Выбрать файл на замену" else "Выбрать файл",
                        { onPickFiles(item) },
                        secondary = true,
                        enabled = !reply.uploading && !reply.sending,
                    )
                    if (item.isPhoto) TextLink("Сделать фото", { onTakePhoto(item) }, centered = true)
                }
            }
        }
        if (reply.uploading) Caption("Загружаем файлы…")
        if (reply.uploads.isNotEmpty()) {
            Label("Загружено сейчас")
            // Качество фото сервер проверяет уже после загрузки: берём свежий статус из обращения.
            for (upload in reply.uploads) {
                UploadRow(upload, claim.files.find { it.id == upload.result.id } ?: upload.result)
            }
        }
        reply.error?.let { Notice(it) }
        Notice("Исходные сведения сохранены. Дополнение попадёт в историю.", blue = true)
    }
}

@Composable
private fun UploadRow(upload: UploadedFile, file: ClaimFileDto) {
    val (text, background, color) =
        when (file.status) {
            FileStatus.REJECTED -> Triple("Не принят", InsuranceColors.RedSoft, InsuranceColors.Red)
            FileStatus.DUPLICATE ->
                Triple("Уже загружен", InsuranceColors.Field, InsuranceColors.Muted)
            FileStatus.UPLOADED ->
                Triple("Проверяем качество", InsuranceColors.BlueSoft, InsuranceColors.Blue)
            else -> Triple("Принят", InsuranceColors.GreenSoft, InsuranceColors.Green)
        }
    InsuranceCard(background = InsuranceColors.Field, border = null, spacing = 6.dp) {
        InfoRow(
            if (file.status == FileStatus.REJECTED) Res.drawable.file_red else Res.drawable.file,
            upload.name,
            upload.documentTypeName,
        )
        StatusBadge(text, background, color)
        // Подсказка сервера: что сделать с отклонённым файлом.
        file.hint?.let { Caption(it, color = InsuranceColors.Red) }
    }
}

private val InfoRequestItemDto.isPhoto get() = documentType?.contains("PHOTO") == true

private fun itemIcon(item: InfoRequestItemDto) =
    when {
        item.isFulfilled -> Res.drawable.check_green
        item.fieldName != null -> Res.drawable.edit_muted
        item.isPhoto -> Res.drawable.camera_amber
        else -> Res.drawable.file_amber
    }

fun itemTitle(item: InfoRequestItemDto, claim: ClaimDetails): String {
    item.fieldName?.let { name ->
        return ClaimField.entries.find { it.apiName == name }?.title ?: name
    }
    val type = item.documentTypeName ?: item.documentType ?: "Документ"
    val replaced = item.fileId?.let { id -> claim.files.find { it.id == id }?.originalName }
    return if (replaced != null) "$type: замените $replaced" else type
}

fun itemSubtitle(item: InfoRequestItemDto): String =
    listOfNotNull(
            when (item.issue) {
                MaterialIssue.LOW_QUALITY -> "Плохое качество"
                MaterialIssue.INVALID_FORMAT -> "Неподходящий формат"
                MaterialIssue.MISSING -> null
            },
            item.comment,
            item.hint,
            if (item.fieldName == null && item.minCount > 1) "Не меньше ${filesCount(item.minCount)}"
            else null,
        )
        // Сервер может положить один и тот же текст и в comment, и в hint.
        .distinct()
        .joinToString(" · ")
        .ifEmpty { if (item.fieldName != null) "Уточните сведения" else "Добавьте файл" }

private fun requestTitle(count: Int) =
    when {
        count == 1 -> "Добавьте один материал"
        count % 10 in 2..4 && count % 100 !in 12..14 -> "Добавьте $count материала"
        else -> "Добавьте $count материалов"
    }

/** Текущее значение запрошенного поля — подставляется в форму ответа. */
fun currentFieldValue(claim: ClaimDetails, field: String): String =
    when (field) {
        ClaimField.DESCRIPTION.apiName -> claim.description
        ClaimField.EVENT_LOCATION.apiName -> claim.eventLocation
        ClaimField.CONTACT_PHONE.apiName -> claim.contactPhone
        else ->
            (claim.details[field] as? JsonPrimitive)?.let {
                if (it.isString) it.content else it.toString()
            }
    }.orEmpty()
