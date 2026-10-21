package ru.tinsurance.server

import io.ktor.server.testing.*
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.*

/** Проверки, которые схема БД гарантирует сама, независимо от кода сервиса. */
class SchemaTest {

    @BeforeTest
    fun migrate() = testApplication {
        environment { config = TestDatabase.config() }
        application { module() }
        startApplication()
    }

    private fun <T> db(block: Connection.() -> T): T = TestDatabase.connection().use { conn ->
        conn.autoCommit = false
        try {
            conn.block()
        } finally {
            conn.rollback() // каждый тест оставляет БД чистой
        }
    }

    private fun Connection.queryLong(sql: String): Long =
        createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }

    private fun Connection.newDraftClaim(): Long {
        val client = queryLong(
            "insert into users(role, full_name, phone, password_hash) " +
                "values ('CLIENT', 'Тест', '+7${System.nanoTime()}', 'x') returning id"
        )
        return queryLong("insert into claims(client_id) values ($client) returning id")
    }

    private fun assertRejected(message: String, block: () -> Unit) {
        val error = assertFailsWith<SQLException> { block() }
        assertTrue(error.message.orEmpty().contains(message), "Unexpected error: ${error.message}")
    }

    @Test
    fun referenceDataLoaded() = db {
        assertEquals(9, queryLong("select count(*) from claim_statuses"))
        assertTrue(queryLong("select count(*) from status_transitions") > 0)
        assertTrue(queryLong("select count(*) from required_documents") > 0)
    }

    @Test
    fun claimNumberGenerated() = db {
        val id = newDraftClaim()
        val number = createStatement().use { st ->
            st.executeQuery("select claim_number from claims where id = $id").use { rs -> rs.next(); rs.getString(1) }
        }
        assertTrue(number.matches(Regex("TS-\\d{7}")), number)
    }

    @Test
    fun illegalTransitionRejected() = db {
        val id = newDraftClaim()
        assertRejected("Недопустимый переход") {
            createStatement().use { it.executeUpdate("update claims set status = 'QUEUED' where id = $id") }
        }
    }

    @Test
    fun cannotSubmitWithoutRequiredFields() = db {
        val id = newDraftClaim()
        assertRejected("claims_required_fields_chk") {
            createStatement().use { it.executeUpdate("update claims set status = 'SUBMITTED' where id = $id") }
        }
    }

    @Test
    fun clientCannotPerformSystemTransition() = db {
        val id = newDraftClaim()
        val clientId = queryLong("select client_id from claims where id = $id")
        assertRejected("Актор CLIENT не может") {
            createStatement().use {
                it.executeUpdate(
                    "insert into claim_status_history(claim_id, from_status, to_status, actor_type, actor_id) " +
                        "values ($id, 'SUBMITTED', 'IN_ANALYSIS', 'CLIENT', $clientId)"
                )
            }
        }
    }

    @Test
    fun operatorOverrideKeepsOriginalAndRequiresReason() = db {
        val id = newDraftClaim()
        val operator = queryLong(
            "insert into users(role, full_name, email, password_hash) " +
                "values ('OPERATOR', 'Оператор', 'o-${System.nanoTime()}@test', 'x') returning id"
        )
        val systemDecision = queryLong(
            "insert into claim_decisions(claim_id, kind, source, value, confidence) " +
                "values ($id, 'ROUTING', 'SYSTEM', '{\"direction\":\"STANDARD\"}', 0.55) returning id"
        )

        // без причины исправление не принимается
        createStatement().execute("savepoint s")
        assertRejected("claim_decisions_action_chk") {
            createStatement().use {
                it.executeUpdate(
                    "insert into claim_decisions(claim_id, kind, source, action, value, supersedes_id, created_by, is_current) " +
                        "values ($id, 'ROUTING', 'OPERATOR', 'OVERRIDE', '{}', $systemDecision, $operator, false)"
                )
            }
        }
        createStatement().execute("rollback to savepoint s")

        createStatement().use {
            it.executeUpdate("update claim_decisions set is_current = false where id = $systemDecision")
            it.executeUpdate(
                "insert into claim_decisions(claim_id, kind, source, action, value, reason, supersedes_id, created_by) " +
                    "values ($id, 'ROUTING', 'OPERATOR', 'OVERRIDE', '{\"direction\":\"MANUAL_REVIEW\"}', " +
                    "'Два участника', $systemDecision, $operator)"
            )
        }
        assertEquals(2, queryLong("select count(*) from claim_decisions where claim_id = $id"))
        assertEquals(1, queryLong("select count(*) from claim_decisions where claim_id = $id and is_current"))
    }

    @Test
    fun clientMustHavePhoneAndStaffMustHaveEmail() = db {
        createStatement().execute("savepoint s")
        assertRejected("users_login_by_role_chk") {
            createStatement().use {
                it.executeUpdate("insert into users(role, full_name, email, password_hash) values ('CLIENT', 'Без телефона', 'x@test', 'x')")
            }
        }
        createStatement().execute("rollback to savepoint s")
        assertRejected("users_login_by_role_chk") {
            createStatement().use {
                it.executeUpdate("insert into users(role, full_name, phone, password_hash) values ('OPERATOR', 'Без почты', '+70000000000', 'x')")
            }
        }
    }

    @Test
    fun onlyOneOpenInfoRequestPerClaim() = db {
        val id = newDraftClaim()
        createStatement().use {
            it.executeUpdate("insert into info_requests(claim_id, source, message) values ($id, 'SYSTEM', 'Загрузите СТС')")
        }
        assertRejected("info_requests_one_open_uq") {
            createStatement().use {
                it.executeUpdate("insert into info_requests(claim_id, source, message) values ($id, 'SYSTEM', 'Ещё запрос')")
            }
        }
    }

    @Test
    fun clientListShowsRequiredAction() = db {
        val id = newDraftClaim()
        val request = queryLong(
            "insert into info_requests(claim_id, source, message) values ($id, 'SYSTEM', 'Загрузите СТС') returning id"
        )
        createStatement().use {
            it.executeUpdate(
                "insert into info_request_items(request_id, document_type) values ($request, 'VEHICLE_REGISTRATION')"
            )
        }
        assertEquals(1, queryLong("select count(*) from client_claim_list where id = $id and action_required"))
        // черновик в веб-очередь оператора не попадает
        assertEquals(0, queryLong("select count(*) from operator_claim_list where id = $id"))
    }
}
