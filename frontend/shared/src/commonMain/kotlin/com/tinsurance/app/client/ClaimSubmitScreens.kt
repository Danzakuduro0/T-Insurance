package com.tinsurance.app.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tinsurance.app.ui.*
import ru.tinsurance.api.ClaimFileDto
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.FileStatus
import ru.tinsurance.api.PolicyDto
import ru.tinsurance.api.mobile.ClaimDetails
import ru.tinsurance.api.mobile.EventTypeDto
import ru.tinsurance.api.mobile.FormDocumentDto
import tinsurance.shared.generated.resources.*

// Экраны 05–07 нового обращения на данных API: проверка качества фото, проверка перед отправкой,
// «Обращение отправлено». Демо-версии — в MaterialScreens.kt, ClaimFormScreens.kt и ClaimStatusScreens.kt.

/** Фото, которые сервер отклонил или ещё проверяет, — их показывает экран 05. */
fun ClaimDetails.photosToReview(documents: List<FormDocumentDto>): List<ClaimFileDto> {
    val photoTypes = documents.filter { it.isPhoto }.map { it.documentType }.toSet()
    return files.filter {
        it.documentType in photoTypes &&
            (it.status == FileStatus.REJECTED || it.status == FileStatus.UPLOADED)
    }
}

@Composable
fun DraftPhotoQualityScreen(
    claim: ClaimDetails,
    documents: List<FormDocumentDto>,
    reply: ReplyState,
    onPickFiles: (FormDocumentDto) -> Unit,
    onTakePhoto: (FormDocumentDto) -> Unit,
    onDelete: (ClaimFileDto) -> Unit,
) {
    ScreenBody {
        PageTitle("Проверьте фотографии")
        val toReview = claim.photosToReview(documents)
        if (toReview.isEmpty()) {
            InfoRow(Res.drawable.check_green, "Все фото приняты", color = InsuranceColors.Green)
            Caption("Можно переходить к проверке обращения.")
        }
        for (file in toReview) {
            val doc = documents.find { it.documentType == file.documentType }
            InsuranceCard(spacing = 10.dp) {
                if (file.status == FileStatus.UPLOADED) {
                    InfoRow(
                        Res.drawable.clock_blue,
                        file.originalName,
                        "Проверяем качество…",
                        InsuranceColors.Blue,
                    )
                } else {
                    StatusBadge(
                        "Снимок не подходит",
                        InsuranceColors.AmberSoft,
                        InsuranceColors.Amber,
                        fullWidth = true,
                    )
                    InfoRow(Res.drawable.file_red, file.originalName, doc?.name, InsuranceColors.Red)
                    // Подсказка сервера: что не так и как переснять.
                    file.hint?.let { Notice(it, Res.drawable.camera_blue) }
                    if (doc != null) {
                        InsuranceButton(
                            "Сделать новое фото",
                            { onTakePhoto(doc) },
                            enabled = !reply.uploading,
                        )
                        TextLink("Выбрать из файлов", { onPickFiles(doc) }, centered = true)
                    }
                    TextLink("Удалить этот снимок", { onDelete(file) }, centered = true)
                }
            }
        }
        if (reply.uploading) Caption("Загружаем файлы…")
        reply.error?.let { Notice(it) }
        Caption("Остальные материалы сохранены")
    }
}

@Composable
fun DraftReviewScreen(
    claim: ClaimDetails,
    type: EventTypeDto?,
    policy: PolicyDto?,
    review: ReviewState,
    confirmed: Boolean,
    onConfirm: (Boolean) -> Unit,
    onEdit: (ClientScreen) -> Unit,
) {
    ScreenBody {
        PageTitle("Всё верно?")
        InsuranceCard(onClick = { onEdit(ClientScreen.Event) }) {
            InfoRow(
                Res.drawable.calendar,
                listOfNotNull(claim.eventTypeName, claim.eventAt?.let { formatDateTime(it) })
                    .joinToString(" · "),
                claim.eventLocation ?: "Место не указано",
                trailing = Res.drawable.edit_muted,
            )
            claim.description?.let { Caption(it) }
        }
        InsuranceCard(onClick = { onEdit(ClientScreen.Contract) }) {
            InfoRow(
                Res.drawable.car,
                listOfNotNull(policy?.carName, policy?.let { productName(it.product) }, claim.policyNumber)
                    .joinToString(" · "),
                policy?.let { "до ${formatDate(it.validTo, withYear = true)}" },
                trailing = Res.drawable.edit_muted,
            )
        }
        if (claim.participants.isNotEmpty()) {
            val others = claim.participants.filter { it.role != "APPLICANT_DRIVER" }
            InsuranceCard(onClick = { onEdit(ClientScreen.Event) }) {
                InfoRow(
                    Res.drawable.team,
                    "Участников: ${claim.participants.size}",
                    others.joinToString { it.fullName }.ifEmpty { null },
                    trailing = Res.drawable.edit_muted,
                )
            }
        }
        InsuranceCard(onClick = { onEdit(ClientScreen.Materials) }) {
            val files = claim.visibleFiles
            InfoRow(
                Res.drawable.file,
                "Материалы · ${filesCount(files.size)}",
                type?.documents?.joinToString { doc ->
                    "${doc.name}: ${files.count { it.documentType == doc.documentType }} из ${doc.minCount}"
                },
                trailing = Res.drawable.edit_muted,
            )
        }
        InsuranceCard(onClick = { onEdit(ClientScreen.Event) }) {
            InfoRow(
                Res.drawable.mail,
                "Уведомления по телефону",
                claim.contactPhone ?: "Телефон не указан",
                trailing = Res.drawable.edit_muted,
            )
        }
        CompletenessNotice(review)
        InsuranceCheckbox("Подтверждаю, что сведения верны", confirmed, onConfirm)
        review.error?.let { Notice(it) }
    }
}

@Composable
private fun CompletenessNotice(review: ReviewState) {
    val check = review.completeness
    when {
        review.loading -> Caption("Проверяем, всё ли добавлено…")
        check == null -> Unit
        check.complete ->
            InfoRow(Res.drawable.check_green, "Всё необходимое добавлено", color = InsuranceColors.Green)
        else -> {
            if (check.missingFields.isNotEmpty())
                Notice("Заполните, чтобы отправить: ${check.missingFields.joinToString { it.title }}")
            if (check.missingDocuments.isNotEmpty())
                Notice(
                    "Не хватает: " +
                        check.missingDocuments.joinToString {
                            "${it.documentTypeName} (${it.uploaded} из ${it.required})"
                        } +
                        ". Можно отправить и сейчас — мы сразу попросим дослать недостающее.",
                    Res.drawable.alert_amber,
                )
            if (check.problemFiles.isNotEmpty())
                Notice(
                    "Есть отклонённые файлы: ${filesCount(check.problemFiles.size)}. " +
                        "Замените их на шаге «Фото и документы».",
                    Res.drawable.alert_amber,
                )
        }
    }
}

@Composable
fun DraftSentScreen(state: ClaimState, onRetry: () -> Unit) {
    ScreenBody {
        val claim = state.details
        if (claim == null) {
            LoadingOrError(state, onRetry)
            return@ScreenBody
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(96.dp).background(InsuranceColors.Yellow, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                InsuranceIcon(Res.drawable.check_success, 56.dp)
            }
        }
        PageTitle("Обращение отправлено", centered = true)
        Text(
            "№ ${claim.claimNumber}",
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        // Без части документов сервер принял обращение и сразу запросил недостающее.
        val needsInfo = claim.status == ClaimStatus.NEEDS_INFO
        Text(
            if (needsInfo) "Не хватает части документов — добавьте их, и мы начнём проверку."
            else "Проверим материалы и сообщим, если понадобится уточнение.",
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (needsInfo) InsuranceColors.Amber else InsuranceColors.Muted,
            textAlign = TextAlign.Center,
        )
        Timeline(timelineItems(claim, state.timeline), large = true)
        Notice("История и документы всегда под рукой", Res.drawable.file_notice)
    }
}
