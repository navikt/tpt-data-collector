package no.nav

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import no.nav.datastore.FakeDatastore
import no.nav.github.FakeGitHub
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MainTest {
    @Test
    fun `server starts and responds with 200 OK`() = testApplication {
        application {
            businessModule(FakeGitHub(), FakeDatastore(), "bogus key")
            naisModule(FakeGitHub(), FakeDatastore())
        }
        val response = client.get("/internal/isAlive")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }
}