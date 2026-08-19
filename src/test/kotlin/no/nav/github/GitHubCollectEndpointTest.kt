package no.nav.github

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import no.nav.FakeWhodis
import no.nav.config.ApplikasjonsConfig
import no.nav.datastore.FakeDatastore
import no.nav.businessModule
import no.nav.kafka.DummyKafkaSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GitHubCollectEndpointTest {

    @Test
    fun `POST collect-github without auth returns 401`() = testApplication {
        application {
            businessModule(FakeGitHub(), FakeDatastore(), DummyKafkaSender(), FakeWhodis(), ApplikasjonsConfig())
        }
        val response = client.post("/collect/github") {
            contentType(ContentType.Application.Json)
            setBody("""{"teams":["my-team"]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
