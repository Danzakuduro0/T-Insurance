package com.tinsurance.app.operator

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "13 · Очередь обращений", widthDp = 1440, heightDp = 900)
@Composable
fun OperatorQueuePreview() = OperatorApp(OperatorScreen.Queue)

@Preview(name = "14 · Карточка случая", widthDp = 1440, heightDp = 900)
@Composable
fun OperatorCasePreview() = OperatorApp(OperatorScreen.Case)

@Preview(name = "15 · Материалы и анализ фотографии", widthDp = 1440, heightDp = 900)
@Composable
fun OperatorMaterialsPreview() = OperatorApp(OperatorScreen.Materials)

@Preview(name = "16 · Ручное исправление", widthDp = 1440, heightDp = 900)
@Composable
fun OperatorCorrectionPreview() = OperatorApp(OperatorScreen.Correction)

@Preview(name = "17 · Запрос дополнительных материалов", widthDp = 1440, heightDp = 900)
@Composable
fun OperatorRequestPreview() = OperatorApp(OperatorScreen.Request)

@Preview(name = "18 · История изменений", widthDp = 1440, heightDp = 900)
@Composable
fun OperatorHistoryPreview() = OperatorApp(OperatorScreen.History)
