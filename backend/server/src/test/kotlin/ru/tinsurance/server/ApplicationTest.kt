package ru.tinsurance.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class ApplicationTest {

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = TestDatabase.config() }
        application { module() }
        block()
    }

    @Test
    fun testRoot() = withApp {
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Hello, Ktor!", response.bodyAsText())
    }

    @Test
    fun healthShowsAppliedSchemaVersion() = withApp {
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"UP","schemaVersion":"5"}""", response.bodyAsText())
    }
}
