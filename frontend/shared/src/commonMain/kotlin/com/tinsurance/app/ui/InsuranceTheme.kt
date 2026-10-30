package com.tinsurance.app.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import tinsurance.shared.generated.resources.*

object InsuranceColors {
    val Yellow = Color(0xFFFFDD2D)
    val Ink = Color(0xFF222222)
    val Muted = Color(0xFF757A80)
    val Field = Color(0xFFF0F1F3)
    val Line = Color(0xFFE3E6EA)
    val Blue = Color(0xFF2477E8)
    val BlueSoft = Color(0xFFEAF2FF)
    val Green = Color(0xFF209C63)
    val GreenSoft = Color(0xFFE8F7EE)
    val Amber = Color(0xFFB36A16)
    val AmberSoft = Color(0xFFFFF3D8)
    val Red = Color(0xFFD94351)
    val RedSoft = Color(0xFFFFF0F1)
}

@Composable
fun InsuranceTheme(content: @Composable () -> Unit) {
    val inter =
        FontFamily(
            Font(Res.font.inter_regular, FontWeight.Normal),
            Font(Res.font.inter_semibold, FontWeight.SemiBold),
            Font(Res.font.inter_bold, FontWeight.Bold),
        )
    fun text(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) =
        TextStyle(
            fontFamily = inter,
            fontSize = size.sp,
            lineHeight = height.sp,
            fontWeight = weight,
            letterSpacing = 0.sp,
            lineHeightStyle =
                LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        )
    MaterialTheme(
        colorScheme =
            lightColorScheme(
                primary = InsuranceColors.Blue,
                onPrimary = Color.White,
                primaryContainer = InsuranceColors.Yellow,
                onPrimaryContainer = InsuranceColors.Ink,
                background = Color.White,
                surface = Color.White,
                onSurface = InsuranceColors.Ink,
                onBackground = InsuranceColors.Ink,
                error = InsuranceColors.Red,
            ),
        typography =
            Typography(
                headlineSmall = text(24, 32, FontWeight.Bold),
                titleMedium = text(18, 24, FontWeight.Bold),
                labelLarge = text(14, 20, FontWeight.SemiBold),
                bodyMedium = text(14, 20),
                bodySmall = text(12, 16),
                labelSmall = text(10, 14),
            ),
        content = {
            CompositionLocalProvider(
                LocalContentColor provides InsuranceColors.Ink,
                content = content,
            )
        },
    )
}
