package no.nav.github

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubTransportTest {
    private val epoch = Instant.parse("2026-11-11T12:00:00Z")
    private val url = "https://api.github.com/repos/navikt/repo"
    private val token = "private-installation-token"

    private data class Response(
        val status: Int = 200,
        val body: String = """{"name":"repo","archived":false}""",
        val headers: Map<String, String> = emptyMap()
    )

    private suspend fun TestScope.withTransport(
        responses: List<Response> = listOf(Response()),
        policy: GitHubTransportPolicy = GitHubTransportPolicy(),
        jitter: (Duration) -> Duration = { it },
        block: suspend (GitHubTransport, List<Long>, HttpClient) -> Unit
    ) {
        val times = mutableListOf<Long>()
        val engine = MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                val response = responses[minOf(times.size, responses.lastIndex)]
                times += currentTime
                respond(response.body, HttpStatusCode.fromValue(response.status), Headers.build {
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    response.headers.forEach { (name, value) -> append(name, value) }
                })
            }
        })
        HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                connectTimeoutMillis = 2000
                requestTimeoutMillis = 5000
            }
            expectSuccess = true
        }.use { client ->
            block(GitHubTransport(
                client, policy, testScheduler.timeSource, { epoch + currentTime.milliseconds }, jitter
            ), times, client)
        }
    }

    private suspend fun GitHubTransport.read(): ReposForTeamResponse =
        execute(GitHubOperation.TEAM_REPOSITORIES, "team") {
            request(HttpMethod.Get, url, token).body()
        }

    @Test
    fun `success returns immediately and nullable results are not timeouts`() = runTest {
        withTransport { transport, times, _ ->
            assertEquals("repo", transport.read().name)
            assertEquals(listOf(0L), times)
            assertNull(transport.execute<String?>(GitHubOperation.FILE_CONTENTS) { null })
            assertEquals(0L, currentTime)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 403, 404, 409, 422, 501, 505])
    fun `permanent HTTP failures are not retried`(status: Int) = runTest {
        withTransport(listOf(Response(status))) { transport, times, _ ->
            val failure = assertThrows<GitHubTransportException> { transport.read() }
            assertEquals(status, failure.response?.status?.value)
            assertEquals(GitHubStopReason.PERMANENT, failure.stopReason)
            assertEquals(listOf(0L), times)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [500, 502, 503, 504])
    fun `transient HTTP failures can recover`(status: Int) = runTest {
        withTransport(listOf(Response(status), Response())) { transport, times, _ ->
            assertEquals("repo", transport.read().name)
            assertEquals(listOf(0L, 1000L), times)
        }
    }

    @Test
    fun `exhausted attempts propagate the last failure`() = runTest {
        withTransport(listOf(Response(503))) { transport, times, _ ->
            val failure = assertThrows<GitHubTransportException> { transport.read() }
            assertEquals(GitHubStopReason.ATTEMPTS_EXHAUSTED, failure.stopReason)
            assertEquals(GitHubFailure.SERVER, failure.failure)
            assertEquals(3, failure.attempt)
            assertEquals(listOf(0L, 1000L, 3000L), times)
        }
    }

    @Test
    fun `backoff caps and uses controllable bounded jitter`() = runTest {
        val ceilings = mutableListOf<Duration>()
        withTransport(
            listOf(Response(503)),
            policy = GitHubTransportPolicy(maxAttempts = 5, maxBackoff = 2.seconds),
            jitter = { ceilings += it; it / 2 }
        ) { transport, times, _ ->
            assertThrows<GitHubTransportException> { transport.read() }
            assertEquals(listOf(1.seconds, 2.seconds, 2.seconds, 2.seconds), ceilings)
            assertEquals(listOf(0L, 500L, 1500L, 2500L, 3500L), times)
        }
    }

    @Test
    fun `primary reset waits use the clock and preserve response metadata`() = runTest {
        val headers = mapOf(
            "X-RateLimit-Remaining" to "0",
            "X-RateLimit-Reset" to (epoch.epochSeconds + 3).toString(),
            "X-GitHub-Request-Id" to "ABCD:1234"
        )
        withTransport(listOf(Response(403, headers = headers), Response())) { transport, times, _ ->
            assertEquals("repo", transport.read().name)
            assertEquals(listOf(0L, 3000L), times)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [403, 429, 503])
    fun `Retry-After seconds are a minimum wait even with smaller jitter`(status: Int) = runTest {
        withTransport(
            listOf(Response(status, headers = mapOf("Retry-After" to "2")), Response()),
            jitter = { Duration.ZERO }
        ) { transport, times, _ ->
            transport.read()
            assertEquals(listOf(0L, 2000L), times)
        }
    }

    @Test
    fun `Retry-After HTTP dates are supported`() = runTest {
        withTransport(listOf(
            Response(429, headers = mapOf("Retry-After" to "Wed, 11 Nov 2026 12:00:04 GMT")), Response()
        )) { transport, times, _ ->
            transport.read()
            assertEquals(listOf(0L, 4000L), times)
        }
    }

    @Test
    fun `both primary reset and Retry-After constraints must be respected`() = runTest {
        withTransport(listOf(Response(403, headers = mapOf(
            "X-RateLimit-Remaining" to "0",
            "X-RateLimit-Reset" to (epoch.epochSeconds + 4).toString(),
            "Retry-After" to "2"
        )), Response())) { transport, times, _ ->
            transport.read()
            assertEquals(listOf(0L, 4000L), times)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["API rate limit exceeded", "You have exceeded a secondary rate limit.", "abuse detection"])
    fun `rate limits without timing headers do not fall back to a short retry`(message: String) = runTest {
        withTransport(listOf(Response(403, """{"message":"$message"}"""))) { transport, times, _ ->
            val failure = assertThrows<GitHubTransportException> { transport.read() }
            assertEquals(
                if (message.startsWith("API")) GitHubFailure.PRIMARY_RATE_LIMIT else GitHubFailure.SECONDARY_RATE_LIMIT,
                failure.failure
            )
            assertEquals(GitHubStopReason.TIME_BUDGET, failure.stopReason)
            assertEquals(listOf(0L), times)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["-1", "1.5", "tomorrow", "", "999999999999999999999999", "9223372036854775807"])
    fun `invalid Retry-After values fail explicitly without sleeping`(value: String) = runTest {
        withTransport(listOf(Response(429, headers = mapOf("Retry-After" to value)))) { transport, times, _ ->
            val failure = assertThrows<GitHubTransportException> { transport.read() }
            assertEquals(GitHubStopReason.INVALID_RATE_LIMIT_WAIT, failure.stopReason)
            assertEquals(listOf(0L), times)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["-1", "invalid", "999999999999999999999", "9223372036854775807"])
    fun `invalid reset values fail explicitly`(value: String) = runTest {
        withTransport(listOf(Response(403, headers = mapOf(
            "X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to value
        )))) { transport, times, _ ->
            val failure = assertThrows<GitHubTransportException> { transport.read() }
            assertEquals(GitHubStopReason.INVALID_RATE_LIMIT_WAIT, failure.stopReason)
            assertEquals(listOf(0L), times)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["20", "60", "86400"])
    fun `rate limit waits cannot consume or exceed the entire budget`(value: String) = runTest {
        withTransport(listOf(Response(429, headers = mapOf("Retry-After" to value)))) { transport, times, _ ->
            val failure = assertThrows<GitHubTransportException> { transport.read() }
            assertEquals(GitHubStopReason.TIME_BUDGET, failure.stopReason)
            assertEquals(listOf(0L), times)
        }
    }

    @Test
    fun `past reset values use bounded backoff instead of a negative delay`() = runTest {
        withTransport(listOf(Response(403, headers = mapOf(
            "X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to (epoch.epochSeconds - 1).toString()
        )), Response())) { transport, times, _ ->
            transport.read()
            assertEquals(listOf(0L, 1000L), times)
        }
    }

    @Test
    fun `rate limit retries still have a finite attempt budget`() = runTest {
        withTransport(listOf(Response(429, headers = mapOf("Retry-After" to "1")))) { transport, times, _ ->
            val failure = assertThrows<GitHubTransportException> { transport.read() }
            assertEquals(GitHubStopReason.ATTEMPTS_EXHAUSTED, failure.stopReason)
            assertEquals(listOf(0L, 1000L, 3000L), times)
        }
    }

    @Test
    fun `remaining elapsed budget includes time spent in failed attempts`() = runTest {
        withTransport { transport, _, _ ->
            var attempts = 0
            val failure = assertThrows<GitHubTransportException> {
                transport.execute(GitHubOperation.FILE_CONTENTS) {
                    attempts++
                    delay(19.seconds)
                    throw IOException("private network error")
                }
            }
            assertEquals(GitHubStopReason.TIME_BUDGET, failure.stopReason)
            assertEquals(1, attempts)
            assertEquals(19000L, currentTime)
        }
    }

    @Test
    fun `total deadline bounds attempts and decoding even when they suspend`() = runTest {
        withTransport { transport, _, _ ->
            var attempts = 0
            val failure = assertThrows<GitHubTransportException> {
                transport.execute(GitHubOperation.FILE_CONTENTS) {
                    attempts++
                    delay(7.seconds)
                    throw IOException("private network error")
                }
            }
            assertEquals(GitHubFailure.DEADLINE, failure.failure)
            assertEquals(3, attempts)
            assertEquals(20000L, currentTime)
        }
    }

    @Test
    fun `malformed successful response bodies are never retried`() = runTest {
        withTransport(listOf(Response(body = """{"private":"response-payload"}"""))) { transport, times, _ ->
            val failure = assertThrows<GitHubTransportException> { transport.read() }
            assertEquals(GitHubFailure.INVALID_RESPONSE, failure.failure)
            assertEquals(200, failure.response?.status?.value)
            assertEquals(listOf(0L), times)
            assertFalse(failure.stackTraceToString().contains("response-payload"))
            assertNull(failure.cause)
        }
    }

    @Test
    fun `network and timeout failures have distinct retryable classifications`() = runTest {
        val failures = listOf(
            IOException("private") to GitHubFailure.NETWORK,
            ConnectTimeoutException("private") to GitHubFailure.CONNECT_TIMEOUT,
            SocketTimeoutException("private") to GitHubFailure.SOCKET_TIMEOUT,
            HttpRequestTimeoutException(HttpRequestBuilder().apply { url(this@GitHubTransportTest.url) }) to
                GitHubFailure.REQUEST_TIMEOUT
        )
        withTransport { transport, _, _ ->
            failures.forEach { (original, kind) ->
                val start = currentTime
                var attempts = 0
                val failure = assertThrows<GitHubTransportException> {
                    transport.execute(GitHubOperation.FILE_CONTENTS) {
                        attempts++
                        throw original
                    }
                }
                assertEquals(kind, failure.failure)
                assertEquals(3, attempts)
                assertEquals(3000L, currentTime - start)
                assertNull(failure.cause)
            }
        }
    }

    @Test
    fun `TLS validation failures are permanent`() = runTest {
        withTransport { transport, _, _ ->
            var attempts = 0
            val failure = assertThrows<GitHubTransportException> {
                transport.execute(GitHubOperation.FILE_CONTENTS) {
                    attempts++
                    throw SSLHandshakeException("private certificate details")
                }
            }
            assertEquals(GitHubFailure.TLS, failure.failure)
            assertEquals(1, attempts)
            assertEquals(0L, currentTime)
        }
    }

    @Test
    fun `operations not safe to replay fail after one attempt`() = runTest {
        withTransport(listOf(Response(503))) { transport, times, _ ->
            val failure = assertThrows<GitHubTransportException> {
                transport.execute(GitHubOperation.INSTALLATION_TOKEN, replay = GitHubReplay.NEVER) {
                    request(HttpMethod.Post, url, token)
                }
            }
            assertEquals(GitHubStopReason.REPLAY_UNSAFE, failure.stopReason)
            assertEquals(listOf(0L), times)
        }
    }

    @Test
    fun `parent deadlines propagate rather than turning into transport failures`() = runTest {
        withTransport { transport, _, _ ->
            assertThrows<CancellationException> {
                withTimeout(2.seconds) {
                    transport.execute(GitHubOperation.FILE_CONTENTS) { awaitCancellation() }
                }
            }
            assertEquals(2000L, currentTime)
        }
    }

    @Test
    fun `cancellation during backoff stops before another request`() = runTest {
        withTransport { transport, _, _ ->
            var attempts = 0
            val work = async {
                transport.execute(GitHubOperation.FILE_CONTENTS) {
                    attempts++
                    throw IOException("private")
                }
            }
            runCurrent()
            work.cancel()
            assertThrows<CancellationException> { work.await() }
            assertEquals(1, attempts)
            assertEquals(0L, currentTime)
        }
    }

    @Test
    fun `GitHub timeout overrides do not change unrelated shared client requests`() = runTest {
        val requests = mutableListOf<Triple<Long?, Long?, Long?>>()
        val engine = MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                val timeout = request.getCapabilityOrNull(HttpTimeoutCapability)
                requests += Triple(timeout?.connectTimeoutMillis, timeout?.requestTimeoutMillis, timeout?.socketTimeoutMillis)
                respond("ok")
            }
        })
        HttpClient(engine) {
            install(HttpTimeout) {
                connectTimeoutMillis = 2000
                requestTimeoutMillis = 5000
            }
        }.use { client ->
            val transport = GitHubTransport(client, timeSource = testScheduler.timeSource)
            transport.execute(GitHubOperation.FILE_CONTENTS) { request(HttpMethod.Get, url, token) }
            client.get("https://backend.example/callback")
            assertEquals(Triple(2000L, 5000L, 5000L), requests[0])
            assertEquals(2000L, requests[1].first)
            assertEquals(5000L, requests[1].second)
            assertNull(requests[1].third)
        }
    }

    @Test
    fun `diagnostics retain useful metadata but no credentials bodies or throwable`() = runTest {
        withCapturedLogs { events ->
            withTransport(listOf(Response(
                503, body = """{"secret":"private-response-payload"}""",
                headers = mapOf("X-GitHub-Request-Id" to "ABCD:1234", "Set-Cookie" to "private-cookie")
            ))) { transport, _, _ ->
                val failure = assertThrows<GitHubTransportException> {
                    transport.execute(GitHubOperation.FILE_CONTENTS, "navikt/repo") {
                        request(HttpMethod.Get, "$url?private=query-secret", token).body<String>()
                    }
                }
                val messages = events.map { it.message.formattedMessage }
                assertEquals(3, messages.size)
                assertTrue(messages.all {
                    it.contains("operation=FILE_CONTENTS") && it.contains("context=navikt/repo") &&
                        it.contains("status=503") && it.contains("requestId=ABCD:1234")
                })
                assertTrue(messages.first().contains("attempt=1") && messages.first().contains("decision=retry"))
                assertTrue(messages.last().contains("attempt=3") && messages.last().contains("decision=stop"))
                assertTrue(events.all { it.thrown == null })
                val diagnostic = messages.joinToString() + failure.stackTraceToString()
                listOf(token, "private-response-payload", "private-cookie", "query-secret").forEach {
                    assertFalse(diagnostic.contains(it))
                }
            }
        }
    }

    @Test
    fun `invalid context and request IDs cannot inject diagnostic fields`() = runTest {
        withCapturedLogs { events ->
            withTransport(listOf(Response(401, headers = mapOf("X-GitHub-Request-Id" to "secret=value")))) {
                transport, _, _ ->
                assertThrows<GitHubTransportException> {
                    transport.execute(GitHubOperation.FILE_CONTENTS, "repo secret=value") {
                        request(HttpMethod.Get, url, token)
                    }
                }
                val message = events.single().message.formattedMessage
                assertTrue(message.contains("context=none") && message.contains("requestId=none"))
                assertFalse(message.contains("secret=value"))
            }
        }
    }
}
