package com.tinsurance.app

import com.tinsurance.app.operator.*
import kotlin.test.*

class OperatorStateTest {
    @Test
    fun queueFiltersCombineSearchAssignmentAndPriority() {
        val claims = operatorDemoClaims()
        assertEquals(47, claims.size)
        assertEquals(6, filterClaims(claims, QueueFilter(tab = 1)).size)
        assertEquals(12, filterClaims(claims, QueueFilter(tab = 2)).size)
        assertEquals(listOf("ТС-10428"), filterClaims(claims,
            QueueFilter(query = " test-0421 ", priority = "Повышенный", tab = 1)).map { it.number })
        assertTrue(filterClaims(claims, QueueFilter(query = "TEST-0421", tab = 2)).isEmpty())
    }

    @Test
    fun colleagueClaimsStayReadOnlyEvenWhenActionsAreCalledDirectly() {
        val state = OperatorState()
        val original = state.claim("ТС-10420")
        assertFalse(state.take(original.number))
        assertFalse(state.saveCorrection(original.number, 0, correction()))
        assertFalse(state.requestMaterials(original.number, request()))
        assertFalse(state.complete(original.number, "Проверено"))
        assertEquals(original, state.claim(original.number))
        assertTrue(state.events.isEmpty())
    }

    @Test
    fun takingClaimAssignsItAndUpdatesQueue() {
        val state = OperatorState()
        assertTrue(state.take("ТС-10431"))
        assertTrue(state.claim("ТС-10431").editable)
        assertEquals(11, filterClaims(state.claims, QueueFilter(tab = 2)).size)
        assertEquals("Исполнитель", state.history("ТС-10431").first().action)
    }

    @Test
    fun correctionsStayWithTheirClaimAndPhotoAndRetainPriorResults() {
        val state = OperatorState()
        assertFalse(state.saveCorrection("ТС-10428", 0, correction().copy(reason = " ")))
        assertFalse(state.saveCorrection("ТС-10428", 4, correction()))
        assertTrue(state.saveCorrection("ТС-10428", 0, correction()))
        assertTrue(state.saveCorrection("ТС-10428", 0, correction().copy(result = "Трещина")))
        assertNull(state.correction("ТС-10428", 1))
        assertNull(state.correction("ТС-10431", 0))
        val events = state.history("ТС-10428").filter { it.correction != null }
        assertEquals("Вмятина", events[0].before)
        assertEquals("Царапина", events[1].before)
        assertEquals("Вмятина", events[1].correction?.result)
        assertTrue(state.saveCorrection("ТС-10428", 2, correction()))
        assertEquals("Не определено", state.history("ТС-10428").first().before)
        assertEquals("Анализ недоступен", state.history("ТС-10428").first().originalResult)
    }

    @Test
    fun requestRequiresMaterialsMessageAndReasonAndChangesStatus() {
        val state = OperatorState()
        assertFalse(state.requestMaterials("ТС-10428", request().copy(photo = false, notice = false)))
        assertFalse(state.requestMaterials("ТС-10428", request().copy(message = "")))
        assertFalse(state.requestMaterials("ТС-10428", request().copy(reason = " ")))
        assertTrue(state.requestMaterials("ТС-10428", request()))
        assertEquals("Нужно уточнение", state.claim("ТС-10428").status)
        assertEquals(request(), state.requests["ТС-10428"])
        assertEquals(1, state.history("ТС-10428").count { it.notification })
    }

    @Test
    fun completedClaimCannotBeChangedOrTakenAgain() {
        val state = OperatorState()
        assertFalse(state.complete("ТС-10428", " "))
        assertTrue(state.complete("ТС-10428", "Материалы проверены"))
        assertFalse(state.take("ТС-10428"))
        assertFalse(state.saveCorrection("ТС-10428", 0, correction()))
        assertFalse(state.requestMaterials("ТС-10428", request()))
    }

    private fun correction() = PhotoCorrection("Вмятина", "Ручная проверка", "Повышенный", DefaultCorrectionReason)
    private fun request() = MaterialRequest(true, true, "Пришлите материалы", "Не хватает извещения")
}
