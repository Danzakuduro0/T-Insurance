package com.tinsurance.app.operator

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tinsurance.app.ui.*
import org.jetbrains.compose.resources.painterResource
import tinsurance.shared.generated.resources.*

@Composable
fun OperatorMaterialsScreen(
    claim: OperatorClaim,
    selected: Int,
    correction: PhotoCorrection?,
    onSelect: (Int) -> Unit,
    onCorrect: () -> Unit,
    onRequest: () -> Unit,
    onDownload: (Int) -> Unit,
) {
    var zoom by rememberSaveable(selected, claim.number) { mutableStateOf(100) }
    OperatorBody {
        OperatorHeading("${claim.number} · Материалы",
            if (selected < 4) "Фото ${selected + 1} из 4 · Извещение о ДТП" else "Извещение о ДТП · PDF")
        InsuranceCard(padding = 16.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OperatorValue("Клиент", claim.client, Modifier.width(180.dp))
                OperatorValue("Договор", claim.policy, Modifier.width(160.dp))
                OperatorValue("Автомобиль", claim.car.replace(" 2019", ", 2019"), Modifier.width(170.dp))
                OperatorValue("Дата события", "16.10.2026, 18:35", Modifier.width(240.dp))
                OperatorValue("Регистрация", "17.10.2026, 09:41", Modifier.width(238.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            InsuranceCard(Modifier.width(208.dp), padding = 12.dp, spacing = 12.dp) {
                Label("Материалы дела")
                repeat(5) { index ->
                    Row(
                        Modifier.fillMaxWidth().height(74.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (selected == index) OperatorSelected else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable(role = Role.Tab, onClickLabel = if (index < 4) "Фото ${index + 1}" else "Извещение.pdf") { onSelect(index) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (index < 4) OperatorPhoto(Modifier.width(52.dp).height(46.dp), Res.drawable.operator_photo_thumbnail)
                        else InsuranceIcon(Res.drawable.operator_pdf, 32.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Caption(if (index < 4) "Фото ${index + 1}" else "Извещение.pdf", color = InsuranceColors.Ink)
                            Text(if (index == 2) "Анализ ожидается" else if (index < 4) "JPG · 2,1 МБ" else "PDF · 1,2 МБ",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (index == 2) InsuranceColors.Blue else InsuranceColors.Muted)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (selected < 4) {
                    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(12.dp))) {
                        val width = maxWidth
                        Box(Modifier.fillMaxSize().graphicsLayer(scaleX = zoom / 100f, scaleY = zoom / 100f)) {
                            OperatorPhoto(Modifier.fillMaxSize())
                            // Область повреждения экспортирована из Figma отдельно от фотографии.
                            if (selected != 2) Image(
                                painterResource(Res.drawable.operator_damage_outline), "Область повреждения",
                                Modifier.offset(x = width * (180f / 560f), y = width * (225f / 560f))
                                    .width(width * (240f / 560f)).height(width * (155f / 560f)),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(40.dp).background(InsuranceColors.Field).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OperatorIconButton(Res.drawable.operator_zoom, "Сбросить масштаб", { zoom = 100 }, 20.dp)
                        Caption("$zoom%", Modifier.width(80.dp), InsuranceColors.Ink)
                        OperatorIconButton(Res.drawable.operator_plus, "Увеличить фото", { zoom = if (zoom >= 200) 100 else zoom + 25 }, 20.dp)
                        OperatorIconButton(Res.drawable.operator_download, "Скачать фото", { onDownload(selected) }, 20.dp)
                        Caption("Фото ${selected + 1} · исходный файл", Modifier.weight(1f))
                    }
                    OperatorNotice("Анализ фото 3 временно недоступен. Файл сохранён, доступна ручная проверка.")
                } else {
                    InsuranceCard(padding = 24.dp, spacing = 16.dp) {
                        InsuranceIcon(Res.drawable.operator_pdf, 64.dp)
                        OperatorTitle("Извещение.pdf")
                        Caption("PDF · 3 страницы · 1,2 МБ")
                        Text("Извещение о дорожно-транспортном происшествии")
                        InsuranceButton("Открыть / скачать PDF", { onDownload(4) })
                    }
                }
            }
            if (selected < 4) {
                InsuranceCard(Modifier.width(384.dp), padding = 16.dp, spacing = 12.dp) {
                    OperatorTitle("Результат анализа")
                    OperatorBadge(
                        when { correction != null -> "Проверено специалистом"; selected == 2 -> "Анализ ожидается"; else -> "Нужна ручная проверка" },
                        Modifier.fillMaxWidth(),
                        when { correction != null -> "success"; selected == 2 -> "info"; else -> "warning" },
                    )
                    OperatorValue(if (correction == null) "Вероятное повреждение" else "Результат специалиста",
                        correction?.result ?: if (selected == 2) "Не определено" else "Царапина")
                    if (selected != 2) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Caption("Уверенность модели", Modifier.weight(1f))
                            Label("64%")
                        }
                        Box(Modifier.fillMaxWidth().height(8.dp).background(InsuranceColors.Field, RoundedCornerShape(4.dp))) {
                            Box(Modifier.fillMaxWidth(.64f).fillMaxHeight().background(InsuranceColors.Yellow, RoundedCornerShape(4.dp)))
                        }
                        Caption("Порог уверенности — 75%")
                        OperatorValue("Деталь", "Передняя левая дверь · 87%")
                    }
                    Label("Проверки качества фото")
                    if (selected == 2) Caption("Автоматическая проверка пока недоступна")
                    else listOf("Резкость — подходит", "Освещение — подходит", "Разрешение подходит").forEach { text ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            InsuranceIcon(Res.drawable.operator_check_green, 22.dp)
                            Label(text, Modifier.weight(1f), InsuranceColors.Green)
                        }
                    }
                    InsuranceButton("Проверить результат", onCorrect, enabled = claim.editable)
                    InsuranceButton("Запросить другое фото", onRequest, secondary = true, enabled = claim.editable)
                    if (!claim.editable) Caption(if (claim.colleague) "Только просмотр · ${claim.operator}" else "Возьмите обращение в работу для изменения")
                }
            }
        }
    }
}
