package no.nav

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Paths
import no.nav.config.ApplikasjonsConfig
import no.nav.datastore.FakeDatastore
import no.nav.github.FakeGitHub
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KtorModulesTest {
    @Test
    fun `server starts and responds to liveness probe`() = testApplication {
        application {
            businessModule(FakeGitHub(), FakeDatastore(), ApplikasjonsConfig())
            naisModule(FakeGitHub(), FakeDatastore())
        }
        val response = client.get("/internal/isAlive")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GH webhooks must have mac auth present in header`() = testApplication {
        application {
            businessModule(FakeGitHub(), FakeDatastore(), ApplikasjonsConfig())
        }
        val response = client.post("/webhook/github")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `Correct signature grants access to webhook endpoint`() = testApplication {
        application {
            businessModule(FakeGitHub(), FakeDatastore(), ApplikasjonsConfig())
        }
        val path = Paths.get("src/test/resources/github_push_webhook.json")
        val requestBody = Files.readString(path)
        val response = client.post("/webhook/github") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
            // Signature calculated using the default dummy secret from ApplikasjonsConfig
            header("X-Hub-Signature-256", "sha256=468d95ef1e0ef6b498a78f0b46a3485c65bcc115c136f18a045aa6433bcf313e")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `Incorrect signature is denied by webhook endpoint`() = testApplication {
        application {
            businessModule(FakeGitHub(), FakeDatastore(), ApplikasjonsConfig())
        }
        val path = Paths.get("src/test/resources/github_push_webhook.json")
        val requestBody = Files.readString(path)
        val response = client.post("/webhook/github") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
            // Signature calculated using the default dummy secret from ApplikasjonsConfig
            header("X-Hub-Signature-256", "sha256=568d75ef1e0ef6b498c78f0b46a3484c65bcc115c136f18a045aa6433bcf313e")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
