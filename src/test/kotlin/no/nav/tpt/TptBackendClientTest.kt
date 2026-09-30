package no.nav.tpt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import no.nav.checks.CheckResult
import no.nav.checks.CheckResultsForRepo
import no.nav.checks.Severity
import no.nav.github.GitHubRepositoryMessage
import no.nav.github.GitHubSyncEvent
import no.nav.metrics.TPTMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TptBackendClientTest {
    private val fastRetries = RetryPolicy(maxAttempts = 3, initialDelay = 1.milliseconds, maxDelay = 5.milliseconds)
    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()

    private fun client(vararg responses: suspend MockRequestHandleScope.() -> HttpResponseData): TptBackendClient {
        var call = 0
        val engine = MockEngine { request ->
            requests += request
            bodies += String(request.body.toByteArray())
            responses[minOf(call++, responses.size - 1)]()
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = true
        }
        return TptBackendClient(httpClient, "http://tpt-backend", { "m2m-token" }, fastRetries)
    }

    private val ok: suspend MockRequestHandleScope.() -> HttpResponseData = { respond("", HttpStatusCode.NoContent) }
    private fun error(status: HttpStatusCode): suspend MockRequestHandleScope.() -> HttpResponseData = { respondError(status) }

    @Test
    fun `posts vulnerability data with bearer token to the callback endpoint`() = runBlocking {
        val message = GitHubRepositoryMessage("navikt/repo", listOf("team"), emptyList())

        assertTrue(client(ok).sendVulnerabilityData(message))

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("http://tpt-backend/callbacks/github/vulnerabilities", request.url.toString())
        assertEquals("Bearer m2m-token", request.headers[HttpHeaders.Authorization])
        assertEquals(ContentType.Application.Json, request.body.contentType?.withoutParameters())
        assertEquals(message, Json.decodeFromString<GitHubRepositoryMessage>(bodies.single()))
    }

    @Test
    fun `posts each callback type to its own path`() = runBlocking {
        val client = client(ok)
        val event = GitHubSyncEvent(listOf("team"), "2026-01-01T10:00:00Z")
        val checks = CheckResultsForRepo(
            "repo", listOf("team"),
            listOf(CheckResult.AllGood("check", "desc", Severity.LOW, Clock.System.now()))
        )

        client.sendSyncStarted(event)
        client.sendCheckResults(checks)
        client.sendSyncComplete(event)

        assertEquals(
            listOf("/callbacks/github/sync/started", "/callbacks/checks", "/callbacks/github/sync/complete"),
            requests.map { it.url.encodedPath }
        )
        assertEquals(event, Json.decodeFromString<GitHubSyncEvent>(bodies[0]))
        assertEquals(checks, Json.decodeFromString<CheckResultsForRepo>(bodies[1]))
    }

    @Test
    fun `retries server errors until the callback succeeds`() = runBlocking {
        val client = client(error(HttpStatusCode.ServiceUnavailable), error(HttpStatusCode.InternalServerError), ok)

        assertTrue(client.sendSyncStarted(GitHubSyncEvent(emptyList(), "now")))
        assertEquals(3, requests.size)
    }

    @Test
    fun `retries network errors`() = runBlocking {
        val client = client({ throw IOException("connection reset") }, ok)

        assertTrue(client.sendSyncStarted(GitHubSyncEvent(emptyList(), "now")))
        assertEquals(2, requests.size)
    }

    @Test
    fun `counts callbacks that fail after all retries`() = runBlocking {
        val before = TPTMetrics.callbacksFailed(SYNC_COMPLETE)

        assertFalse(client(error(HttpStatusCode.BadGateway)).sendSyncComplete(GitHubSyncEvent(emptyList(), "now")))

        assertEquals(fastRetries.maxAttempts, requests.size)
        assertEquals(before + 1, TPTMetrics.callbacksFailed(SYNC_COMPLETE))
    }

    @Test
    fun `does not retry client errors`() = runBlocking {
        val before = TPTMetrics.callbacksFailed(CHECK_RESULTS)

        assertFalse(client(error(HttpStatusCode.BadRequest)).sendCheckResults(CheckResultsForRepo("repo", emptyList(), emptyList())))

        assertEquals(1, requests.size)
        assertEquals(before + 1, TPTMetrics.callbacksFailed(CHECK_RESULTS))
    }

    @Test
    fun `retries when fetching the token fails`() = runBlocking {
        var tokenCalls = 0
        val engine = MockEngine { request -> requests += request; respond("", HttpStatusCode.NoContent) }
        val client = TptBackendClient(HttpClient(engine), "http://tpt-backend", {
            if (tokenCalls++ == 0) throw IOException("texas unavailable") else "m2m-token"
        }, fastRetries)

        assertTrue(client.sendSyncStarted(GitHubSyncEvent(emptyList(), "now")))
        assertEquals(1, requests.size)
        assertEquals(2, tokenCalls)
    }

    @Test
    fun `backoff grows exponentially and is capped`() {
        val policy = RetryPolicy(maxAttempts = 10, initialDelay = 1.seconds, maxDelay = 5.seconds)
        assertEquals(
            listOf(1.seconds, 2.seconds, 4.seconds, 5.seconds, 5.seconds),
            (2..6).map { policy.delayBeforeAttempt(it) }
        )
    }

    @Test
    fun `texas token provider requests an entra id token for the target`() = runBlocking {
        val engine = MockEngine { request ->
            requests += request
            bodies += String(request.body.toByteArray())
            respond(
                """{"access_token":"the-token","expires_in":3599,"token_type":"Bearer"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val token = TexasTokenProvider(httpClient, "http://texas/token", "api://prod-gcp.appsec.tpt-backend/.default").token()

        assertEquals("the-token", token)
        assertEquals("http://texas/token", requests.single().url.toString())
        assertEquals(
            TexasTokenRequest("entra_id", "api://prod-gcp.appsec.tpt-backend/.default"),
            Json.decodeFromString<TexasTokenRequest>(bodies.single())
        )
    }
}
