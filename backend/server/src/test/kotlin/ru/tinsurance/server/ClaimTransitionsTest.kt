package ru.tinsurance.server

import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.server.claims.ClaimTransitions
import io.ktor.server.testing.*
import kotlin.test.*

class ClaimTransitionsTest {

    @BeforeTest
    fun migrate() = testApplication {
        environment { config = TestDatabase.config() }
        application { module() }
        startApplication()
    }

    private fun <T> rows(sql: String, map: (java.sql.ResultSet) -> T): List<T> = TestDatabase.connection().use { c ->
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
    }

    @Test
    fun kotlinGraphMatchesDatabase() {
        val db = rows("select from_status, to_status, allowed_actors from status_transitions") { rs ->
            (rs.getString(1) to rs.getString(2)) to (rs.getArray(3).array as Array<*>).map { it.toString() }.toSet()
        }.toMap()
        val kotlin = ClaimTransitions.allowed.entries.associate { (k, v) ->
            (k.first.name to k.second.name) to v.map { it.name }.toSet()
        }
        assertEquals(db, kotlin, "Граф в ClaimTransitions.kt разошёлся с таблицей status_transitions")
    }

    @Test
    fun statusesMatchAndNoPayoutDecision() {
        val db = rows("select code from claim_statuses") { it.getString(1) }.toSet()
        assertEquals(ClaimStatus.entries.map { it.name }.toSet(), db)
        // система не принимает решение о выплате
        assertTrue(db.none { it.contains("PAY") || it.contains("APPROV") || it.contains("REJECT") }, db.toString())
    }
}
