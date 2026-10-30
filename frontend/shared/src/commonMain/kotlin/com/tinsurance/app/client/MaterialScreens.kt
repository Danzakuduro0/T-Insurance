package com.tinsurance.app.client

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tinsurance.app.ui.*
import tinsurance.shared.generated.resources.*

data class Attachment(val name: String, val bytes: ByteArray, val preview: ImageBitmap? = null) {
    val isPhoto: Boolean
        get() = !name.endsWith(".pdf", ignoreCase = true)
}

@Composable
fun MaterialsScreen(
    tab: Int,
    onTab: (Int) -> Unit,
    attachments: List<Attachment>,
    photoReplaced: Boolean,
    formatError: Boolean,
    onUpload: () -> Unit,
    onPhoto: (Boolean) -> Unit,
    onRemove: (Attachment) -> Unit,
    onDocument: () -> Unit,
) {
    ScreenBody {
        PageTitle("Добавьте материалы")
        SegmentedTabs(listOf("Фото", "Документы"), tab, onTab)
        InsuranceCard(padding = 16.dp, spacing = 8.dp, onClick = onUpload) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InsuranceIcon(Res.drawable.plus_blue, 24.dp)
                Label("Загрузить файлы", color = InsuranceColors.Blue)
            }
            Caption("JPEG, PNG, PDF · до 20 МБ")
        }
        if (tab == 0) {
            InsuranceCard(spacing = 12.dp) {
                InfoRow(
                    Res.drawable.check_green,
                    "Фото общего вида",
                    "Добавлено 1 фото",
                    InsuranceColors.Green,
                )
                InfoRow(
                    if (photoReplaced) Res.drawable.check_green else Res.drawable.alert_amber,
                    "Фото повреждения крупно",
                    if (photoReplaced) "Фото добавлено" else "Требуется минимум 1 фото",
                    if (photoReplaced) InsuranceColors.Green else InsuranceColors.Amber,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DamagePhoto(
                    modifier = Modifier.weight(1f).height(112.dp),
                    onClick = { onPhoto(false) },
                )
                if (!photoReplaced)
                    DamagePhoto(
                        dark = true,
                        modifier = Modifier.weight(1f).height(112.dp),
                        onClick = { onPhoto(true) },
                    )
                else {
                    val photo = attachments.lastOrNull { it.isPhoto }
                    if (photo?.preview != null) {
                        Image(
                            photo.preview,
                            photo.name,
                            Modifier.weight(1f).height(112.dp).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
            if (!photoReplaced) Caption("Это фото уже добавлено")
            attachments
                .filter { it.isPhoto }
                .forEach { attachment ->
                    InsuranceCard {
                        InfoRow(Res.drawable.file, attachment.name, "Добавлено с устройства")
                        TextLink("Удалить", { onRemove(attachment) })
                    }
                }
            if (formatError) {
                InsuranceCard(background = InsuranceColors.RedSoft, border = null) {
                    InfoRow(
                        Res.drawable.file_red,
                        "scan.heic",
                        "Формат не поддерживается",
                        InsuranceColors.Red,
                    )
                    TextLink("Заменить файл", onUpload)
                }
            }
            Caption("Сфотографируйте повреждение ближе, при хорошем освещении.")
        } else {
            InsuranceCard(background = InsuranceColors.Field, border = null, onClick = onDocument) {
                InfoRow(
                    Res.drawable.file,
                    "Извещение_ДТП.pdf",
                    "Тестовый документ",
                    trailing = Res.drawable.download,
                )
            }
            attachments
                .filter { !it.isPhoto }
                .forEach { attachment ->
                    InsuranceCard {
                        InfoRow(Res.drawable.file, attachment.name, "Добавлено с устройства")
                        TextLink("Удалить", { onRemove(attachment) })
                    }
                }
            Caption("Все страницы документа должны быть видны целиком, а текст — читаться.")
        }
    }
}

@Composable
fun PhotoQualityScreen() {
    ScreenBody {
        PageTitle("Проверьте фотографию")
        DamagePhoto(dark = true, modifier = Modifier.fillMaxWidth().height(250.dp))
        StatusBadge(
            "Снимок слишком тёмный",
            InsuranceColors.AmberSoft,
            InsuranceColors.Amber,
            fullWidth = true,
        )
        InsuranceCard(spacing = 10.dp) {
            InfoRow(Res.drawable.alert_amber, "Недостаточно света", color = InsuranceColors.Amber)
            InfoRow(Res.drawable.check_green, "Формат подходит", color = InsuranceColors.Green)
            InfoRow(Res.drawable.check_green, "Файл загружен", color = InsuranceColors.Green)
        }
        Notice(
            "Снимите дверь при дневном свете. Повреждение должно быть видно крупно.",
            Res.drawable.camera_blue,
        )
        Caption("Остальные материалы сохранены")
    }
}

@Composable
fun ReplyScreen(
    draft: ClaimDraft,
    claimNumber: String,
    photo: Attachment?,
    onRequest: () -> Unit,
    onUpload: () -> Unit,
    onDocument: () -> Unit,
) {
    ScreenBody {
        Caption("К обращению № $claimNumber")
        TextLink("Запрос от 17 октября, 12:10", onRequest, Res.drawable.file_link)
        Label("Фото передней левой двери")
        if (photo?.preview != null) {
            Image(
                photo.preview,
                photo.name,
                Modifier.fillMaxWidth()
                    .height(156.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onUpload),
                contentScale = ContentScale.Crop,
            )
        } else DamagePhoto(modifier = Modifier.fillMaxWidth().height(156.dp), onClick = onUpload)
        StatusBadge(
            "Новое фото · готово",
            InsuranceColors.GreenSoft,
            InsuranceColors.Green,
            width = 176.dp,
        )
        Label("Извещение о ДТП")
        InsuranceCard(background = InsuranceColors.Field, border = null, onClick = onDocument) {
            InfoRow(
                Res.drawable.file,
                "Извещение_ДТП.pdf",
                "3 страницы · 2,4 МБ",
                trailing = Res.drawable.download,
            )
        }
        InsuranceField("Комментарий", draft.reply, { draft.reply = it }, multiline = true)
        Notice("Исходные сведения сохранены. Дополнение попадёт в историю.", blue = true)
    }
}
