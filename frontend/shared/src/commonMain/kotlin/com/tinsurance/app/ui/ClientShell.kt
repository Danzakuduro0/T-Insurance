package com.tinsurance.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tinsurance.shared.generated.resources.*

@Composable
fun BrandHeader(onNotifications: () -> Unit, onProfile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).background(InsuranceColors.Yellow, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("Т", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(8.dp))
        Label("Страхование")
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(40.dp)
                .clickable(
                    role = Role.Button,
                    onClickLabel = "Уведомления",
                    onClick = onNotifications,
                ),
            contentAlignment = Alignment.Center,
        ) {
            InsuranceIcon(Res.drawable.bell_header, 24.dp)
        }
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier.size(36.dp)
                .background(InsuranceColors.Field, CircleShape)
                .clickable(role = Role.Button, onClickLabel = "Профиль", onClick = onProfile),
            contentAlignment = Alignment.Center,
        ) {
            Caption("АС", color = InsuranceColors.Ink)
        }
    }
}

@Composable
fun BackHeader(title: String, onBack: () -> Unit, step: Int? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 12.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(40.dp)
                .clickable(role = Role.Button, onClickLabel = "Назад", onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            InsuranceIcon(if (step == null) Res.drawable.back else Res.drawable.back_small, 24.dp)
        }
        if (step != null) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Caption("Шаг $step из 4")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(4) { index ->
                        Box(
                            Modifier.weight(1f)
                                .height(4.dp)
                                .background(
                                    if (index < step) InsuranceColors.Yellow
                                    else InsuranceColors.Line,
                                    RoundedCornerShape(2.dp),
                                )
                        )
                    }
                }
            }
        } else Label(title, Modifier.weight(1f))
    }
}

@Composable
fun ClientNavigation(
    notificationsSelected: Boolean = false,
    onClaims: () -> Unit,
    onPolicies: () -> Unit,
    onNotifications: () -> Unit,
    onProfile: () -> Unit,
) {
    val labels = listOf("Обращения", "Полисы", "Уведомления", "Профиль")
    val icons =
        listOf(
            if (notificationsSelected) Res.drawable.nav_claims else Res.drawable.nav_claims_active,
            Res.drawable.nav_policies,
            if (notificationsSelected) Res.drawable.nav_notifications_active
            else Res.drawable.nav_notifications,
            Res.drawable.nav_profile,
        )
    val actions = listOf(onClaims, onPolicies, onNotifications, onProfile)
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .selectableGroup()
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == if (notificationsSelected) 2 else 0
            Column(
                Modifier.weight(1f)
                    .heightIn(min = 56.dp)
                    .selectable(selected, role = Role.Tab, onClick = actions[index]),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                InsuranceIcon(icons[index], 23.dp)
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) InsuranceColors.Blue else InsuranceColors.Muted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// The web preview has no system status bar. Android and iOS use their own insets.
@Composable
fun PreviewStatusBar() {
    Row(
        Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Caption("9:41", Modifier.weight(1f), InsuranceColors.Ink)
        Caption("LTE  ▰", color = InsuranceColors.Ink)
    }
}
