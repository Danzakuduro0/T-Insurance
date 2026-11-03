package com.tinsurance.app.operator

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tinsurance.app.ui.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import tinsurance.shared.generated.resources.*

internal val OperatorBackground = Color(0xFFF5F6F7)
internal val OperatorSelected = Color(0xFFFFF4B0)

@Composable
internal fun OperatorBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
internal fun OperatorHeading(
    title: String,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) Caption(subtitle)
        }
        actions()
    }
}

@Composable
internal fun OperatorTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
internal fun OperatorValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Caption(label)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun OperatorAvatar() {
    Box(Modifier.size(32.dp).background(InsuranceColors.Field, CircleShape), contentAlignment = Alignment.Center) {
        Caption("ЕВ", color = InsuranceColors.Ink)
    }
}

@Composable
internal fun OperatorIconButton(
    icon: DrawableResource,
    description: String,
    onClick: () -> Unit,
    size: Dp = 24.dp,
) {
    Box(
        Modifier.sizeIn(minWidth = size, minHeight = size)
            .clip(RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { InsuranceIcon(icon, size, description) }
}

@Composable
internal fun OperatorInput(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    icon: DrawableResource? = null,
    compact: Boolean = false,
) {
    Row(
        modifier.height(if (compact) 40.dp else 44.dp).background(InsuranceColors.Field, RoundedCornerShape(12.dp))
            .then(if (compact) Modifier else Modifier.border(1.dp, InsuranceColors.Line, RoundedCornerShape(12.dp)))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) InsuranceIcon(icon, 18.dp)
        BasicTextField(
            value, onChange,
            Modifier.weight(1f).semantics { contentDescription = placeholder },
            textStyle = (if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium)
                .copy(color = InsuranceColors.Ink),
            cursorBrush = SolidColor(InsuranceColors.Blue), singleLine = true,
            decorationBox = { input ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = if (compact) InsuranceColors.Muted else InsuranceColors.Ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    input()
                }
            },
        )
    }
}

@Composable
internal fun OperatorSelect(
    value: String,
    placeholder: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    allowAll: Boolean = true,
    label: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Caption(label)
        Box {
            Box(
                Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(InsuranceColors.Field)
                    .border(1.dp, InsuranceColors.Line, RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = label ?: placeholder) { expanded = true }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(value.ifEmpty { placeholder }, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                if (allowAll) DropdownMenuItem(
                    text = { Text("Все · $placeholder") }, onClick = { onSelect(""); expanded = false },
                )
                options.distinct().forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
                }
            }
        }
    }
}

@Composable
internal fun OperatorBadge(text: String, modifier: Modifier = Modifier, tone: String = "neutral") {
    val (background, color) = when (tone) {
        "warning" -> InsuranceColors.AmberSoft to InsuranceColors.Amber
        "info" -> InsuranceColors.BlueSoft to InsuranceColors.Blue
        "success" -> InsuranceColors.GreenSoft to InsuranceColors.Green
        else -> InsuranceColors.Field to InsuranceColors.Muted
    }
    Box(modifier.background(background, CircleShape).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = color,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal fun statusTone(status: String) = when (status) {
    "На рассмотрении", "Проверка материалов" -> "info"
    "Нужно уточнение" -> "warning"
    "Обработка завершена" -> "success"
    else -> "neutral"
}

@Composable
internal fun OperatorPhoto(modifier: Modifier, image: DrawableResource = Res.drawable.operator_photo_door) {
    Image(
        painterResource(image),
        "Фото повреждения передней левой двери",
        modifier.clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop,
    )
}

@Composable
internal fun OperatorNotice(text: String, warning: Boolean = false) {
    InsuranceCard(background = if (warning) InsuranceColors.AmberSoft else InsuranceColors.BlueSoft, border = null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InsuranceIcon(if (warning) Res.drawable.operator_alert else Res.drawable.operator_info, 20.dp)
            Caption(text, Modifier.weight(1f), if (warning) InsuranceColors.Amber else InsuranceColors.Blue)
        }
    }
}

@Composable
internal fun OperatorRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(InsuranceColors.Line))
}
