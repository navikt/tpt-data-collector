package no.nav

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import no.nav.config.ApplikasjonsConfig
import no.nav.datastore.FakeDatastore
import no.nav.github.FakeGitHub
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MainTest {
    @Test
    fun `server starts and responds with 200 OK`() = testApplication {
        application {
            businessModule(FakeGitHub(), FakeDatastore(), ApplikasjonsConfig())
            naisModule(FakeGitHub(), FakeDatastore())
        }
        val response = client.get("/internal/isAlive")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GH webhooks must have mac auth`() = testApplication {
        application {
            businessModule(FakeGitHub(), FakeDatastore(), ApplikasjonsConfig())
        }
        val response = client.post("/webhook/github")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
