package com.tinsurance.app.client

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.tinsurance.app.ui.*
import tinsurance.shared.generated.resources.*

@Composable
fun ClaimsScreen(
    tab: Int,
    onTab: (Int) -> Unit,
    onNew: () -> Unit,
    onProgress: (String) -> Unit,
    onRequest: () -> Unit,
    onCompleted: () -> Unit,
    onPolicies: () -> Unit,
    replySent: Boolean,
    submitted: Boolean,
) {
    ScreenBody(spacing = 10.dp) {
        PageTitle("Обращения")
        InsuranceButton("Сообщить о случае", onNew)
        InsuranceCard(background = InsuranceColors.Field, border = null, onClick = onPolicies) {
            InfoRow(
                Res.drawable.car_small,
                "Kia Rio · КАСКО",
                "TEST-0421 · до 31.12.2026",
                trailing = Res.drawable.chevron_small,
            )
        }
        SegmentedTabs(
            listOf(
                "Активные ${if (submitted) 3 else 2}",
                "Черновики ${if (submitted) 0 else 1}",
                "Завершённые",
            ),
            tab,
            onTab,
        )
        if (tab == 0) {
            if (submitted) {
                InsuranceCard(onClick = { onProgress("ТС-10429") }) {
                    Caption("№ ТС-10429 · 17 октября")
                    Label("Повреждение автомобиля")
                    StatusBadge("Отправлено", InsuranceColors.BlueSoft, InsuranceColors.Blue)
                    Caption("Проверяем материалы")
                }
            }
            InsuranceCard(onClick = { onProgress("ТС-10428") }) {
                Caption("№ ТС-10428 · 17 октября")
                Label("Повреждение автомобиля")
                StatusBadge("На рассмотрении", InsuranceColors.BlueSoft, InsuranceColors.Blue)
                Caption("Материалы у специалиста")
            }
            InsuranceCard(onClick = { if (replySent) onProgress("ТС-10410") else onRequest() }) {
                Caption("№ ТС-10410 · 12 октября")
                Label("Повреждение автомобиля")
                StatusBadge(
                    if (replySent) "Дополнение получено" else "Нужны документы",
                    if (replySent) InsuranceColors.BlueSoft else InsuranceColors.AmberSoft,
                    if (replySent) InsuranceColors.Blue else InsuranceColors.Amber,
                )
                if (replySent) Caption("Проверяем новые материалы")
                else TextLink("Добавить материалы", onRequest, Res.drawable.file_link)
            }
        }
        if (tab != 2 && !submitted) {
            InsuranceCard(
                background = InsuranceColors.Field,
                border = null,
                padding = 10.dp,
                onClick = onNew,
            ) {
                InfoRow(
                    Res.drawable.file_draft,
                    "Черновик сохранён",
                    "Продолжить заполнение",
                    trailing = Res.drawable.chevron_small,
                )
            }
        } else if (tab == 2) {
            InsuranceCard(onClick = onCompleted) {
                Caption("№ ТС-10386 · 10 октября")
                Label("Повреждение автомобиля")
                StatusBadge("Обработка завершена", InsuranceColors.GreenSoft, InsuranceColors.Green)
                TextLink("Открыть сводку", onCompleted)
            }
        }
    }
}
