package no.nav.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import java.io.StringWriter
import java.security.KeyPairGenerator
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import no.nav.checks.CheckResult
import no.nav.checks.Checks
import no.nav.datastore.FakeDatastore
import no.nav.metrics.TPTMetrics
import no.nav.tpt.FakeTptBackend
import no.nav.tpt.TptRequestHandler
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

@OptIn(ExperimentalCoroutinesApi::class)
class DependabotAlertsTest {
    companion object {
        private val privateKeyPem by lazy {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            StringWriter().also { writer -> JcaPEMWriter(writer).use { it.writeObject(keyPair) } }.toString()
        }
        private const val disabledResponse = """
            {
              "message":"Dependabot alerts are disabled for this repository.",
              "documentation_url":"https://docs.github.com/rest/dependabot/alerts#list-dependabot-alerts-for-a-repository",
              "status":"403"
            }
        """
        private const val criticalAlerts = """
            [{"security_advisory":{"vulnerabilities":[
              {"package":{"ecosystem":"npm","name":"example"},"severity":"critical"}
            ]}}]
        """

        @JvmStatic
        fun otherForbiddenResponses(): List<String> = listOf(
            """{"message":"Resource not accessible by integration"}""",
            """{"message":"API rate limit exceeded"}""",
            """{"message":"You have exceeded a secondary rate limit."}""",
            """{"message":"Dependabot alerts are disabled for this repository. More details."}""",
            """{"message":null}""",
            """{"message":["Dependabot alerts are disabled for this repository."]}""",
            "{}",
            "not-json"
        )
    }

    private data class Response(
        val body: String,
        val status: HttpStatusCode = HttpStatusCode.OK,
        val headers: Map<String, String> = emptyMap()
    )

    private suspend fun withGitHub(
        responses: List<Response>,
        tokenResponse: Response = Response("""{"token":"test-token","expires_at":"2099-01-01T00:00:00Z"}"""),
        block: suspend (RealGitHub, List<String>) -> Unit
    ) {
        val paths = mutableListOf<String>()
        var alertRequest = 0
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            paths += path
            val response = if (path == "/app/installations/456/access_tokens") {
                tokenResponse
            } else {
                assertTrue(path.endsWith("/dependabot/alerts"))
                assertEquals("open", request.url.parameters["state"])
                responses[minOf(alertRequest++, responses.lastIndex)]
            }
            respond(response.body, response.status, Headers.build {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                response.headers.forEach { (name, value) -> append(name, value) }
            })
        }
        HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = true
        }.use { client ->
            block(RealGitHub(client, "123", "456", privateKeyPem), paths)
        }
    }

    private suspend fun withCapturedLogs(block: suspend (List<LogEvent>) -> Unit) {
        val context = assertIs<LoggerContext>(LogManager.getContext(false))
        val events = CopyOnWriteArrayList<LogEvent>()
        val appender = object : AbstractAppender("dependabot-log-capture", null, null, true, Property.EMPTY_ARRAY) {
            override fun append(event: LogEvent) {
                events += event.toImmutable()
            }
        }
        val loggers = listOf(
            "retryWithBackoff",
            "no.nav.checks.Checks",
            "no.nav.checks.githubapi.CriticalVulnerabilitiesCheck"
        ).map { context.getLogger(it) }
        appender.start()
        loggers.forEach { it.addAppender(appender) }
        try {
            block(events)
        } finally {
            loggers.forEach { it.removeAppender(appender) }
            appender.stop()
        }
    }

    private fun failedChecks() = TPTMetrics.registry.find("webhooks_failed").counter()?.count() ?: 0.0
    private fun alertRequests(paths: List<String>) = paths.count { it.endsWith("/dependabot/alerts") }

    @Test
    fun `disabled alerts are explicit and are not retried`() = runTest {
        withCapturedLogs { events ->
            withGitHub(listOf(Response(disabledResponse, HttpStatusCode.Forbidden))) { gitHub, paths ->
                assertEquals(DependabotAlertsResult.Disabled, gitHub.dependabotOpenSecurityAlertsFor("repo"))
                assertEquals(1, alertRequests(paths))
                assertEquals(0L, currentTime)
                assertTrue(events.none { it.level.isMoreSpecificThan(Level.WARN) })
            }
        }
    }

    @Test
    fun `successful empty alert collections remain available`() = runTest {
        withGitHub(listOf(Response("[]"))) { gitHub, paths ->
            assertEquals(DependabotAlertsResult.Available(emptyMap()), gitHub.dependabotOpenSecurityAlertsFor("repo"))
            assertEquals(1, alertRequests(paths))
            assertEquals(0L, currentTime)
        }
    }

    @Test
    fun `available alerts retain package severities`() = runTest {
        withGitHub(listOf(Response(criticalAlerts))) { gitHub, paths ->
            assertEquals(
                DependabotAlertsResult.Available(mapOf("example" to "critical")),
                gitHub.dependabotOpenSecurityAlertsFor("repo")
            )
            assertEquals(1, alertRequests(paths))
        }
    }

    @ParameterizedTest
    @MethodSource("otherForbiddenResponses")
    fun `other forbidden responses remain failures under the existing retry policy`(body: String) = runTest {
        withGitHub(listOf(Response(body, HttpStatusCode.Forbidden))) { gitHub, paths ->
            val failure = assertThrows<ResponseException> { gitHub.dependabotOpenSecurityAlertsFor("repo") }
            assertEquals(HttpStatusCode.Forbidden, failure.response.status)
            assertEquals(3, alertRequests(paths))
            assertEquals(3000L, currentTime)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["Retry-After", "X-RateLimit-Remaining"])
    fun `rate limit headers take precedence over a disabled message`(header: String) = runTest {
        val value = if (header == "Retry-After") "60" else "0"
        withGitHub(listOf(Response(disabledResponse, HttpStatusCode.Forbidden, mapOf(header to value)))) { gitHub, paths ->
            val failure = assertThrows<ResponseException> { gitHub.dependabotOpenSecurityAlertsFor("repo") }
            assertEquals(HttpStatusCode.Forbidden, failure.response.status)
            assertEquals(3, alertRequests(paths))
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 429, 500])
    fun `a disabled message on another status is not a disabled result`(status: Int) = runTest {
        withGitHub(listOf(Response(disabledResponse, HttpStatusCode.fromValue(status)))) { gitHub, paths ->
            val failure = assertThrows<ResponseException> { gitHub.dependabotOpenSecurityAlertsFor("repo") }
            assertEquals(HttpStatusCode.fromValue(status), failure.response.status)
            assertEquals(3, alertRequests(paths))
        }
    }

    @Test
    fun `existing not found empty-result and retry behavior is preserved`() = runTest {
        withGitHub(listOf(Response("""{"message":"Not Found"}""", HttpStatusCode.NotFound))) { gitHub, paths ->
            assertEquals(DependabotAlertsResult.Available(emptyMap()), gitHub.dependabotOpenSecurityAlertsFor("repo"))
            assertEquals(3, alertRequests(paths))
            assertEquals(3000L, currentTime)
        }
    }

    @Test
    fun `a transient failure followed by disabled alerts stops retrying at disabled`() = runTest {
        withGitHub(listOf(
            Response("""{"message":"Unavailable"}""", HttpStatusCode.ServiceUnavailable),
            Response(disabledResponse, HttpStatusCode.Forbidden)
        )) { gitHub, paths ->
            assertEquals(DependabotAlertsResult.Disabled, gitHub.dependabotOpenSecurityAlertsFor("repo"))
            assertEquals(2, alertRequests(paths))
            assertEquals(1000L, currentTime)
        }
    }

    @Test
    fun `token exchange failures cannot be interpreted as disabled alerts`() = runTest {
        withGitHub(
            responses = emptyList(),
            tokenResponse = Response(disabledResponse, HttpStatusCode.Forbidden)
        ) { gitHub, paths ->
            assertThrows<ResponseException> { gitHub.dependabotOpenSecurityAlertsFor("repo") }
            assertEquals(0, alertRequests(paths))
            assertEquals(3, paths.size)
        }
    }

    @Test
    fun `disabled assessment is omitted from callbacks without failure logs or metrics while other checks continue`() = runTest {
        withCapturedLogs { events ->
            withGitHub(listOf(
                Response(disabledResponse, HttpStatusCode.Forbidden),
                Response("[]"),
                Response(criticalAlerts)
            )) { realGitHub, paths ->
                val backend = FakeTptBackend()
                val gitHub = object : FakeGitHub() {
                    override suspend fun allReposForTeam(teamName: String) = listOf("disabled", "empty", "critical")
                    override suspend fun dependabotOpenSecurityAlertsFor(repoName: String) =
                        realGitHub.dependabotOpenSecurityAlertsFor(repoName)
                }
                val before = failedChecks()

                TptRequestHandler(gitHub, Checks(gitHub, FakeDatastore()), backend).runAllChecksFor("team")

                assertEquals(before, failedChecks())
                assertEquals(3, alertRequests(paths))
                assertEquals(listOf("disabled", "empty", "critical"), backend.sentCheckResults.map { it.repoName })
                val byRepo = backend.sentCheckResults.associateBy { it.repoName }
                val disabledResults = byRepo.getValue("disabled").results
                assertTrue(disabledResults.none { it.name == "HasCriticalVulns" })
                assertTrue(disabledResults.any { it.name == "OldDeployments" })
                assertTrue(disabledResults.any { it.name == "GitHubToolingStatus" })
                assertIs<CheckResult.AllGood>(byRepo.getValue("empty").results.single { it.name == "HasCriticalVulns" })
                assertIs<CheckResult.NeedsWork>(byRepo.getValue("critical").results.single { it.name == "HasCriticalVulns" })
                assertTrue(events.none { it.level.isMoreSpecificThan(Level.WARN) })
                val skipped = events.single {
                    it.message.formattedMessage == "Skipping HasCriticalVulns for navikt/disabled: Dependabot alerts are disabled"
                }
                assertEquals(Level.INFO, skipped.level)
                assertNull(skipped.thrown)
            }
        }
    }

    @Test
    fun `ordinary forbidden responses still count as failed checks and are not logged as disabled`() = runTest {
        withCapturedLogs { events ->
            withGitHub(listOf(Response(
                """{"message":"Resource not accessible by integration"}""",
                HttpStatusCode.Forbidden
            ))) { realGitHub, _ ->
                val gitHub = object : FakeGitHub() {
                    override suspend fun dependabotOpenSecurityAlertsFor(repoName: String) =
                        realGitHub.dependabotOpenSecurityAlertsFor(repoName)
                }
                val before = failedChecks()

                val results = Checks(gitHub, FakeDatastore()).runAll("repo", emptySet())

                assertTrue(results.none { it.name == "HasCriticalVulns" })
                assertEquals(before + 1, failedChecks())
                assertTrue(events.any {
                    it.level == Level.WARN && it.message.formattedMessage.startsWith("Failures during checks:")
                })
                assertTrue(events.none { it.message.formattedMessage.startsWith("Skipping HasCriticalVulns") })
            }
        }
    }
}
