package com.tinsurance.app.client

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.tinsurance.app.App

@Preview(name = "01 · Мои обращения", widthDp = 390, heightDp = 844)
@Composable
fun ClaimsPreview() = App(true, ClientScreen.Claims)

@Preview(name = "02 · Договор и тип события", widthDp = 390, heightDp = 844)
@Composable
fun ContractPreview() = App(true, ClientScreen.Contract)

@Preview(name = "03 · Обстоятельства и контакты", widthDp = 390, heightDp = 844)
@Composable
fun EventPreview() = App(true, ClientScreen.Event)

@Preview(name = "04 · Фото и документы", widthDp = 390, heightDp = 844)
@Composable
fun MaterialsPreview() = App(true, ClientScreen.Materials)

@Preview(name = "05 · Проверка качества фото", widthDp = 390, heightDp = 844)
@Composable
fun PhotoQualityPreview() = App(true, ClientScreen.PhotoQuality)

@Preview(name = "06 · Проверка перед отправкой", widthDp = 390, heightDp = 844)
@Composable
fun ReviewPreview() = App(true, ClientScreen.Review)

@Preview(name = "07 · Обращение отправлено", widthDp = 390, heightDp = 844)
@Composable
fun SentPreview() = App(true, ClientScreen.Sent)

@Preview(name = "08 · Ход обработки", widthDp = 390, heightDp = 844)
@Composable
fun ProgressPreview() = App(true, ClientScreen.Progress)

@Preview(name = "09 · Запрос материалов", widthDp = 390, heightDp = 844)
@Composable
fun RequestPreview() = App(true, ClientScreen.Request)

@Preview(name = "10 · Ответ на запрос", widthDp = 390, heightDp = 844)
@Composable
fun ReplyPreview() = App(true, ClientScreen.Reply)

@Preview(name = "11 · Обработка завершена", widthDp = 390, heightDp = 844)
@Composable
fun CompletedPreview() = App(true, ClientScreen.Completed)

@Preview(name = "12 · Центр уведомлений", widthDp = 390, heightDp = 844)
@Composable
fun NotificationsPreview() = App(true, ClientScreen.Notifications)
