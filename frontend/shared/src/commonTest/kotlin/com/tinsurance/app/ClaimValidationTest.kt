package com.tinsurance.app

import com.tinsurance.app.client.*
import kotlin.test.*

class ClaimValidationTest {
    @Test
    fun validDemoDraftHasNoErrors() {
        assertTrue(validateEvent(ClaimDraft()).isEmpty())
    }

    @Test
    fun datesRespectMonthLengthsAndLeapYears() {
        assertTrue(isValidDate("29.02.2024"))
        assertTrue(isValidDate("29.02.2000"))
        assertFalse(isValidDate("29.02.2026"))
        assertFalse(isValidDate("29.02.1900"))
        assertFalse(isValidDate("31.04.2026"))
        assertFalse(isValidDate("00.10.2026"))
        assertFalse(isValidDate("16.13.2026"))
        assertFalse(isValidDate("16/10/2026"))
    }

    @Test
    fun invalidFieldsReturnTheirOwnErrors() {
        val draft =
            ClaimDraft().apply {
                time = "24:01"
                location = " "
                description = ""
                email = "anna@"
            }
        assertEquals(setOf("time", "location", "description", "email"), validateEvent(draft).keys)
    }

    @Test
    fun filesHaveFormatSizeAndCountLimits() {
        assertNull(fileError("PHOTO.JPG", MaxFileSize, 9, 0))
        assertNotNull(fileError("scan.heic", 100, 0, 0))
        assertNotNull(fileError("empty.pdf", 0, 0, 0))
        assertNotNull(fileError("large.png", MaxFileSize + 1, 0, 0))
        assertNotNull(fileError("extra.pdf", 100, 10, 0))
        assertNotNull(fileError("extra.pdf", 100, 0, MaxTotalSize))
    }
}
