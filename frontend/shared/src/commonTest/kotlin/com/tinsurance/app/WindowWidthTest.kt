package com.tinsurance.app

import androidx.compose.ui.unit.dp
import com.tinsurance.app.ui.WindowWidth
import kotlin.test.*

class WindowWidthTest {
    @Test
    fun breakpointsFollowSpecification() {
        // ТЗ 5.2: 320–480 — Mobile S, 481–768 — Mobile L / Tablet, от 769 — Desktop.
        assertEquals(WindowWidth.Compact, WindowWidth.of(320.dp))
        assertEquals(WindowWidth.Compact, WindowWidth.of(480.dp))
        assertEquals(WindowWidth.Medium, WindowWidth.of(481.dp))
        assertEquals(WindowWidth.Medium, WindowWidth.of(768.dp))
        assertEquals(WindowWidth.Expanded, WindowWidth.of(769.dp))
        assertEquals(WindowWidth.Expanded, WindowWidth.of(1280.dp))
    }

    @Test
    fun twoColumnsStartFromTablet() {
        assertFalse(WindowWidth.Compact.atLeastMedium)
        assertTrue(WindowWidth.Medium.atLeastMedium)
        assertTrue(WindowWidth.Expanded.atLeastMedium)
    }
}
