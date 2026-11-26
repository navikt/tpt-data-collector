package no.nav

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.util.logging.KtorSimpleLogger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import no.nav.checks.CheckResultsForRepo
import no.nav.config.ApplikasjonsConfig
import no.nav.datastore.FakeDatastore
import no.nav.github.FakeGitHub
import no.nav.github.withCapturedLogs
import no.nav.tpt.FakeTptBackend
import no.nav.whodis.FakeWhodis
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.LogEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebhookLoggingTest {
    private val loggerName = "no.nav.WebhookLoggingTest"
    private val deliveryId = "12345678-1234-1234-1234-123456789abc"
    private val webhookBody = """
        {"ref":"refs/heads/main","after":"commit","repository":{
        "id":1,"name":"repo","full_name":"navikt/repo","master_branch":"main"},"commits":[]}
    """.trimIndent()

    @Test
    fun `webhook decisions and check summaries retain delivery correlation`() = testApplication {
        val received = CompletableDeferred<CheckResultsForRepo>()
        val backend = object : FakeTptBackend() {
            override suspend fun sendCheckResults(results: CheckResultsForRepo): Boolean {
                received.complete(results)
                return true
            }
        }
        environment { log = KtorSimpleLogger(loggerName) }
        application {
            businessModule(
                FakeGitHub(), FakeDatastore(), backend, FakeWhodis(),
                ApplikasjonsConfig(githubWebhookSecret = "dummy", githubOrganization = "navikt"),
            )
        }

        withCapturedLogs(loggerName) { events ->
            val response = client.post("/webhook/github") {
                setBody(webhookBody)
                header("X-Hub-Signature-256", signature(webhookBody))
                header("X-GitHub-Delivery", deliveryId)
            }

            assertEquals(HttpStatusCode.Accepted, response.status)
            withTimeout(10.seconds) { received.await() }
            val decision = events.single { it.message.formattedMessage == "GitHub webhook accepted" }
            assertEquals(Level.INFO, decision.level)
            assertEquals("github_webhook", decision.contextData.getValue<String>("operation"))
            assertEquals("scheduled", decision.contextData.getValue<String>("outcome"))
            assertEquals("refs/heads/main", decision.contextData.getValue<String>("ref"))
            assertFalse(decision.contextData.containsKey("reason"))
            val summary = events.single { it.message.formattedMessage == "Repository checks finished" }
            listOf(decision, summary).forEach { event ->
                assertEquals("navikt/repo", event.contextData.getValue<String>("repository"))
                assertEquals(deliveryId, event.contextData.getValue<String>("delivery_id"))
            }
        }
    }

    @Test
    fun `only accepted webhook access logs are suppressed`() = testApplication {
        val received = CompletableDeferred<CheckResultsForRepo>()
        val backend = object : FakeTptBackend() {
            override suspend fun sendCheckResults(results: CheckResultsForRepo): Boolean {
                received.complete(results)
                return true
            }
        }
        val accessLogEvents = Channel<LogEvent>(Channel.UNLIMITED)
        environment { log = KtorSimpleLogger(loggerName) }
        application {
            businessModule(
                FakeGitHub(), FakeDatastore(), backend, FakeWhodis(),
                ApplikasjonsConfig(githubWebhookSecret = "dummy", githubOrganization = "navikt"),
            )
            routing {
                post("/logging-test/accepted") { call.respond(HttpStatusCode.Accepted) }
                get("/logging-test/error") { error("Synthetic request failure") }
            }
        }

        withCapturedLogs(loggerName, onEvent = { event ->
            if (event.loggerName == loggerName &&
                (event.message.formattedMessage.contains("/webhook/github") ||
                    event.message.formattedMessage.contains("/logging-test/"))) {
                accessLogEvents.trySend(event)
            }
        }) { events ->
            assertEquals(HttpStatusCode.Accepted, client.post("/webhook/github") {
                setBody(webhookBody)
                header("X-Hub-Signature-256", signature(webhookBody))
            }.status)
            withTimeout(10.seconds) { received.await() }
            assertEquals(HttpStatusCode.Unauthorized, client.post("/webhook/github") {
                setBody(webhookBody)
            }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/webhook/github") {
                setBody(webhookBody)
                header("X-Hub-Signature-256", "sha256=invalid")
            }.status)
            assertEquals(HttpStatusCode.BadRequest, client.post("/webhook/github") {
                setBody("not json")
                header("X-Hub-Signature-256", signature("not json"))
            }.status)
            assertEquals(HttpStatusCode.Accepted, client.post("/logging-test/accepted").status)
            assertEquals(HttpStatusCode.InternalServerError, client.get("/logging-test/error").status)
            val accessLogs = withTimeout(10.seconds) {
                List(5) { accessLogEvents.receive().message.formattedMessage }
            }

            assertFalse(events.any {
                it.loggerName == loggerName &&
                    it.message.formattedMessage.contains("202") &&
                    it.message.formattedMessage.contains("/webhook/github")
            })
            assertEquals(2, accessLogs.count { it.contains("401") && it.contains("/webhook/github") })
            assertTrue(accessLogs.any { it.contains("400") && it.contains("/webhook/github") })
            assertTrue(accessLogs.any { it.contains("202") && it.contains("/logging-test/accepted") })
            assertTrue(accessLogs.any { it.contains("500") && it.contains("/logging-test/error") })
        }
    }

    @Test
    fun `uncaught webhook background failures retain delivery correlation`() = testApplication {
        val failure = IllegalStateException("Synthetic callback failure")
        val loggedFailure = CompletableDeferred<LogEvent>()
        val backend = object : FakeTptBackend() {
            override suspend fun sendCheckResults(results: CheckResultsForRepo): Boolean = throw failure
        }
        environment { log = KtorSimpleLogger(loggerName) }
        application {
            businessModule(
                FakeGitHub(), FakeDatastore(), backend, FakeWhodis(),
                ApplikasjonsConfig(githubWebhookSecret = "dummy", githubOrganization = "navikt"),
            )
        }

        withCapturedLogs(loggerName, onEvent = { event ->
            if (event.loggerName == loggerName && event.message.formattedMessage == "Background job failed") {
                loggedFailure.complete(event)
            }
        }) {
            assertEquals(HttpStatusCode.Accepted, client.post("/webhook/github") {
                setBody(webhookBody)
                header("X-Hub-Signature-256", signature(webhookBody))
                header("X-GitHub-Delivery", deliveryId)
            }.status)

            val event = withTimeout(10.seconds) { loggedFailure.await() }
            assertEquals(Level.ERROR, event.level)
            assertSame(failure, event.thrown)
            assertEquals("github_webhook", event.contextData.getValue<String>("operation"))
            assertEquals("navikt/repo", event.contextData.getValue<String>("repository"))
            assertEquals(deliveryId, event.contextData.getValue<String>("delivery_id"))
        }
    }

    private fun signature(body: String): String =
        "sha256=" + Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec("dummy".toByteArray(), "HmacSHA256"))
        }.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
}
