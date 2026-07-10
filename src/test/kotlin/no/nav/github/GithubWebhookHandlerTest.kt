package no.nav.github

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.client.request.headers
import io.ktor.client.request.setBody
import io.ktor.server.testing.testApplication
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import no.nav.config.ApplikasjonsConfig
import no.nav.generateHmac
import no.nav.module
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class GithubWebhookHandlerTest {
    val config = ApplikasjonsConfig()
    val secretKey = SecretKeySpec(config.githubWebhookSecret.toByteArray(), "HmacSHA256")
    val mac = Mac.getInstance("HmacSHA256").also { it.init(secretKey) }

    @Test
    fun `Should generate Hmac as excpected`() {
        val data = "Hello World"
        val hmac = generateHmac(data, mac)
        assertEquals("sha256=4bde0d92d5842a7aea772bc7b267296c76fc703bde0fd950705aeb403d5ea57d", hmac)
    }

    @Test
    fun `Should fail on wrong http method`() = testApplication {
        application {
            module(testing = true)
        }

        val response = client.get("/webhook/github")
        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
    }

    @Test
    fun `Should fail when signature is missing`() = testApplication {
        application {
            module(testing = true)
        }

        val response = client.post("/webhook/github")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `Should fail when signature is wrong`() = testApplication {
        application {
            module(testing = true)
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
            module(testing = true)
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
            module(testing = true)
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