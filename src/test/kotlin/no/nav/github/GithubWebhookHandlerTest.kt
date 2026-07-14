package no.nav.github

import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import no.nav.datastore.FakeDatastore
import no.nav.generateHmac
import no.nav.businessModule
import org.junit.jupiter.api.Test

class GithubWebhookHandlerTest {
    val secretKey = SecretKeySpec("bogus key".toByteArray(), "HmacSHA256")
    val mac = Mac.getInstance("HmacSHA256").also { it.init(secretKey) }

    @Test
    fun `Should generate Hmac as excpected`() {
        val data = "Hello World"
        val hmac = generateHmac(data, mac)
        assertEquals("sha256=87831b9f63aeceae5a901577bdf0ce1d697f20f8a7aca92cf070708d6d67dcce", hmac)
    }

    @Test
    fun `Should fail on wrong http method`() = testApplication {
        application {
            businessModule(FakeGitHub(), FakeDatastore(), "bogus key")
        }

        val response = client.get("/webhook/github")
        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
    }

    @Test
    fun `Should fail when signature is missing`() = testApplication {
        application {
            businessModule(FakeGitHub(), FakeDatastore(), "bogus key")
        }

        val response = client.post("/webhook/github")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `Should fail when signature is wrong`() = testApplication {
        application {
            businessModule(FakeGitHub(), FakeDatastore(), "bogus key")
        }

        val response = client.post("/webhook/github") {
            headers {
                append("X-Hub-Signature-256", "wrong")
            }
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `Should fail when signature is correct but body is bad`() = testApplication {
        application {
            businessModule(FakeGitHub(), FakeDatastore(), "bogus key")
        }
        val body = Json.encodeToString("Bad body")
        val hmac = generateHmac(body, mac)
        val response = client.post("/webhook/github") {
            headers {
                append("X-Hub-Signature-256", hmac)
            }
            setBody(body)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `Should work ok when everything is OK`() = testApplication {
        application {
            businessModule(FakeGitHub(), FakeDatastore(), "bogus key")
        }

        val body = this::class.java.getResource("/github_push_webhook.json")?.readText() ?: "wops"

        val hmac = generateHmac(body, mac)
        val response = client.post("/webhook/github") {
            headers {
                append("X-Hub-Signature-256", hmac)
                setBody(body)
            }
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }
}