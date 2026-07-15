package no.nav

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import no.nav.datastore.FakeDatastore
import no.nav.github.FakeGitHub
import no.nav.kafka.DummyKafkaSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MainTest {
    @Test
    fun `server starts and responds with 200 OK`() = testApplication {
        application {
            businessModule(FakeGitHub(), FakeDatastore(), "bogus key", DummyKafkaSender())
            naisModule(FakeGitHub(), FakeDatastore())
        }
        val response = client.get("/internal/isAlive")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }
}
