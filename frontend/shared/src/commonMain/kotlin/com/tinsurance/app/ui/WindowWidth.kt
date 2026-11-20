package com.tinsurance.app.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Классы ширины окна по ТЗ (раздел 5.2): 320–480, 481–768 и от 769 px. */
enum class WindowWidth {
    /** Телефон: одна колонка, нижнее меню, карточки. */
    Compact,

    /** Большой телефон и планшет: формы в две колонки. */
    Medium,

    /** Десктоп: боковое меню, разделённый экран. */
    Expanded;

    val atLeastMedium get() = this >= Medium

    /**
     * Сколько места отдаём содержимому. Десктопной раскладки пока нет, поэтому Expanded
     * тоже ограничен планшетной шириной — растянутая на 1280 px одна колонка читается хуже.
     */
    val contentMaxWidth: Dp
        get() =
            when (this) {
                Compact -> 480.dp
                Medium,
                Expanded -> 768.dp
            }

    companion object {
        fun of(width: Dp): WindowWidth =
            when {
                width <= 480.dp -> Compact
                width <= 768.dp -> Medium
                else -> Expanded
            }
    }
}

/** Минимальная область нажатия для кнопок и ссылок (ТЗ 4.2.1: «минимум 44×44 px touch target»). */
val MinTouchTarget = 44.dp

/** Класс ширины для экранов; задаётся в корне приложения по размеру окна. */
val LocalWindowWidth = compositionLocalOf { WindowWidth.Compact }
