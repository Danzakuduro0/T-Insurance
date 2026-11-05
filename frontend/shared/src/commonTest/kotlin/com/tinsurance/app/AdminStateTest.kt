package com.tinsurance.app

import com.tinsurance.app.admin.*
import kotlin.test.*

class AdminStateTest {
    @Test
    fun initialStateMatchesTheDesign() {
        val state = AdminState()
        assertEquals(47, state.claims.size)
        assertEquals(listOf("ТС-10428", "ТС-10431"), state.unassigned.map { it.number })
        assertEquals(5, state.operators.first { it.name == "Мария Орлова" }.available)
        assertNull(rulesError(state.rules))
    }

    @Test
    fun bulkAssignmentPreservesPrioritiesAndRecordsItsAuthorAndReason() {
        val state = AdminState()
        val before = state.claims
        val numbers = state.unassigned.map { it.number }
        assertNull(state.reassign(numbers, "Мария Орлова", KeepPriority, "  Высокая нагрузка  "))
        assertTrue(state.unassigned.isEmpty())
        assertEquals(5, state.operators.first { it.name == "Мария Орлова" }.active)
        assertEquals(8, state.operators.first { it.name == "Мария Орлова" }.queued)
        for (claim in state.claims) {
            val original = before.first { it.number == claim.number }
            assertEquals(original.priority, claim.priority)
            if (claim.number in numbers) assertEquals("Мария Орлова", claim.operator)
            else assertEquals(original, claim)
        }
        assertEquals(2, state.events.size)
        assertTrue(state.events.all { it.author == AdminName && it.reason == "Высокая нагрузка" })
    }

    @Test
    fun unavailableOperatorAndBlankReasonCannotChangeTheQueue() {
        val state = AdminState()
        val claims = state.claims
        val operators = state.operators
        for ((name, reason) in listOf("Илья Морозов" to "Проверка", "Алексей Соколов" to "Проверка",
            "Неизвестный оператор" to "Проверка", "Мария Орлова" to " ")) {
            assertNotNull(state.reassign(listOf("ТС-10428"), name, KeepPriority, reason))
            assertEquals(claims, state.claims)
            assertEquals(operators, state.operators)
            assertTrue(state.events.isEmpty())
        }
    }

    @Test
    fun wholeBatchIsRejectedWhenCapacityOrClaimIdentifiersAreInvalid() {
        val state = AdminState()
        val before = state.claims
        assertNotNull(state.reassign(listOf("ТС-10428", "ТС-10431", "ТС-10420"),
            "Елена Волкова", KeepPriority, "Проверка"))
        assertNotNull(state.reassign(listOf("ТС-10428", "нет такого обращения"),
            "Мария Орлова", KeepPriority, "Проверка"))
        assertEquals(before, state.claims)
        assertTrue(state.events.isEmpty())
    }

    @Test
    fun repeatedIdentifiersAndExistingAssignmentDoNotConsumeCapacityTwice() {
        val state = AdminState()
        assertNull(state.reassign(listOf("ТС-10428", "ТС-10428"), "Мария Орлова", KeepPriority, "Проверка"))
        assertEquals(4, state.operators.first { it.name == "Мария Орлова" }.active)
        assertEquals(1, state.events.size)
        assertNull(state.reassign(listOf("ТС-10428"), "Мария Орлова", "Высокий", "Смена приоритета"))
        assertEquals(4, state.operators.first { it.name == "Мария Орлова" }.active)
        assertEquals("Высокий", state.claims.first { it.number == "ТС-10428" }.priority)
        assertTrue(state.events.first().after.endsWith("Высокий"))
    }

    @Test
    fun invalidRulesAreNotSavedAndValidChangesAreAudited() {
        val state = AdminState()
        val original = state.rules
        for (rules in listOf(original.copy(confidence = 101), original.copy(confidence = -1),
            original.copy(queueHours = 0), original.copy(queueHours = 25), original.copy(materials = emptyList()),
            original.copy(belowThreshold = "Неизвестный маршрут"))) {
            assertNotNull(state.saveRules(rules))
            assertEquals(original, state.rules)
            assertTrue(state.events.isEmpty())
        }
        val changed = original.copy(belowThreshold = "Очередь оператора")
        assertNull(state.saveRules(changed))
        assertEquals(changed, state.rules)
        assertTrue(state.events.single().before.contains("Ручная проверка"))
        assertTrue(state.events.single().after.contains("Очередь оператора"))
        assertNull(state.saveRules(changed))
        assertEquals(1, state.events.size)
    }

    @Test
    fun durationInputAcceptsWholeHoursAndRejectsMalformedValues() {
        assertEquals(2, parseHours("2 часа"))
        assertEquals(24, parseHours("24 часа"))
        assertEquals(1, parseHours("1 час"))
        assertEquals(12, parseHours(" 12 "))
        listOf("-1", "1.5 часа", "позже", "час", "2 дня", "2 часа extra").forEach { assertNull(parseHours(it)) }
    }
}
