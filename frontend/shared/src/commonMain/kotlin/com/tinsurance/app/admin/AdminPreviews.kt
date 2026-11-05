package com.tinsurance.app.admin

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.tinsurance.app.demo.DemoLoginScreen

@Preview(name = "19 · Обзор нагрузки", widthDp = 1440, heightDp = 900)
@Composable
fun AdminOverviewPreview() = AdminApp(AdminScreen.Overview)

@Preview(name = "20 · Команда и переназначение", widthDp = 1440, heightDp = 900)
@Composable
fun AdminTeamPreview() = AdminApp(AdminScreen.Team)

@Preview(name = "21 · Правила обработки", widthDp = 1440, heightDp = 900)
@Composable
fun AdminRulesPreview() = AdminApp(AdminScreen.Rules)

@Preview(name = "22 · Вход в демосценарий", widthDp = 1440, heightDp = 900)
@Composable
fun DemoLoginPreview() = DemoLoginScreen(onOpen = {})
