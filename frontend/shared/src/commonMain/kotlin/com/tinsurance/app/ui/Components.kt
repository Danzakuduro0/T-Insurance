package com.tinsurance.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import tinsurance.shared.generated.resources.*

@Composable
fun InsuranceIcon(image: DrawableResource, size: Dp = 22.dp, description: String? = null) {
    Image(painterResource(image), description, Modifier.size(size), contentScale = ContentScale.Fit)
}

@Composable
fun PageTitle(text: String, centered: Boolean = false) {
    Text(
        text,
        Modifier.fillMaxWidth().semantics { heading() },
        style = MaterialTheme.typography.headlineSmall,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
    )
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = InsuranceColors.Muted) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = InsuranceColors.Ink) {
    Text(text, modifier, style = MaterialTheme.typography.labelLarge, color = color)
}

@Composable
fun ScreenBody(
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
fun InsuranceCard(
    modifier: Modifier = Modifier,
    background: Color = Color.White,
    border: Color? = InsuranceColors.Line,
    padding: Dp = 12.dp,
    spacing: Dp = 6.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val outline = if (border != null) Modifier.border(1.dp, border, shape) else Modifier
    val click =
        if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    // Figma draws these borders outside the padding.
    val inset = padding + if (border != null) 1.dp else 0.dp
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .then(outline)
            .then(click)
            .padding(inset),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
fun InfoRow(
    icon: DrawableResource,
    title: String,
    subtitle: String? = null,
    color: Color = InsuranceColors.Ink,
    trailing: DrawableResource? = null,
    onClick: (() -> Unit)? = null,
) {
    val click =
        if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        Modifier.fillMaxWidth().then(click),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InsuranceIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label(title, color = color)
            if (subtitle != null) Caption(subtitle)
        }
        if (trailing != null) {
            Box(Modifier.offset(x = 6.dp)) { InsuranceIcon(trailing, 18.dp) }
        }
    }
}

@Composable
fun InsuranceButton(
    text: String,
    onClick: () -> Unit,
    secondary: Boolean = false,
    enabled: Boolean = true,
) {
    val background = if (secondary || !enabled) InsuranceColors.Field else InsuranceColors.Yellow
    Box(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, color = if (enabled) InsuranceColors.Ink else InsuranceColors.Muted)
    }
}

@Composable
fun TextLink(
    text: String,
    onClick: () -> Unit,
    icon: DrawableResource? = null,
    centered: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) InsuranceIcon(icon, 18.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = InsuranceColors.Blue)
    }
}

@Composable
fun ActionFooter(
    text: String,
    onClick: () -> Unit,
    secondary: Boolean = false,
    enabled: Boolean = true,
    link: String? = null,
    onLink: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth()
            .padding(
                start = 20.dp,
                end = 20.dp,
                top = 12.dp,
                bottom = if (link != null) 16.dp else 12.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InsuranceButton(text, onClick, secondary, enabled)
        if (link != null) {
            TextLink(link, onLink, centered = true)
        }
    }
}

@Composable
fun StatusBadge(
    text: String,
    background: Color,
    color: Color,
    fullWidth: Boolean = false,
    width: Dp = 164.dp,
) {
    val size = if (fullWidth) Modifier.fillMaxWidth() else Modifier.widthIn(min = width)
    Box(size.background(background, CircleShape).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Caption(text, color = color)
    }
}

@Composable
fun Notice(text: String, icon: DrawableResource = Res.drawable.info_blue, blue: Boolean = false) {
    InsuranceCard(
        background = if (blue) InsuranceColors.BlueSoft else InsuranceColors.Field,
        border = null,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InsuranceIcon(icon, 20.dp)
            Caption(text, Modifier.weight(1f), InsuranceColors.Blue)
        }
    }
}

@Composable
fun SegmentedTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(InsuranceColors.Field, RoundedCornerShape(10.dp))
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        titles.forEachIndexed { index, title ->
            Box(
                Modifier.weight(1f)
                    .heightIn(min = 28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selected == index) Color.White else Color.Transparent)
                    .selectable(selected == index, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Caption(
                    title,
                    color = if (selected == index) InsuranceColors.Ink else InsuranceColors.Muted,
                )
            }
        }
    }
}

@Composable
fun InsuranceField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    multiline: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    error: String? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Caption(label)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .background(
                        InsuranceColors.Field,
                        RoundedCornerShape(if (multiline) 16.dp else 12.dp),
                    )
                    .then(
                        if (multiline && error == null) Modifier
                        else
                            Modifier.border(
                                1.dp,
                                if (error != null) InsuranceColors.Red else InsuranceColors.Line,
                                RoundedCornerShape(12.dp),
                            )
                    )
                    .padding(12.dp)
                    .semantics { contentDescription = label },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = InsuranceColors.Ink),
            cursorBrush = SolidColor(InsuranceColors.Blue),
            singleLine = !multiline,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            decorationBox = { input -> Box(contentAlignment = Alignment.CenterStart) { input() } },
        )
        if (error != null) Caption(error, color = InsuranceColors.Red)
    }
}

@Composable
fun InsuranceCheckbox(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 32.dp)
            .toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp)
                .background(
                    if (checked) InsuranceColors.Yellow else Color.White,
                    RoundedCornerShape(6.dp),
                )
                .border(
                    1.dp,
                    if (checked) InsuranceColors.Yellow else InsuranceColors.Line,
                    RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) InsuranceIcon(Res.drawable.check_black, 16.dp)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun DamagePhoto(
    dark: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val click =
        if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Image(
        painterResource(if (dark) Res.drawable.photo_dark else Res.drawable.photo_damage),
        if (dark) "Тёмное фото повреждения двери" else "Повреждение передней левой двери",
        modifier.clip(RoundedCornerShape(12.dp)).then(click),
        contentScale = ContentScale.Crop,
    )
}

data class TimelineItem(val title: String, val time: String? = null, val status: String = "done")

@Composable
fun Timeline(items: List<TimelineItem>, large: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(if (large) 12.dp else 8.dp)) {
        items.forEach { item ->
            val (background, icon) =
                when (item.status) {
                    "current" -> InsuranceColors.BlueSoft to Res.drawable.clock_blue
                    "waiting" -> InsuranceColors.Field to Res.drawable.clock_muted
                    "request" -> InsuranceColors.AmberSoft to Res.drawable.alert_timeline
                    else -> InsuranceColors.GreenSoft to Res.drawable.check_timeline
                }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(24.dp).background(background, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    InsuranceIcon(icon, 16.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (item.time != null)
                        Text(
                            item.time,
                            style = MaterialTheme.typography.labelSmall,
                            color = InsuranceColors.Muted,
                        )
                    Text(
                        item.title,
                        style =
                            if (large) MaterialTheme.typography.bodyMedium
                            else MaterialTheme.typography.bodySmall,
                        color =
                            if (item.status == "waiting") InsuranceColors.Muted
                            else InsuranceColors.Ink,
                    )
                }
            }
        }
    }
}
