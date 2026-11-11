package no.nav.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import java.io.StringWriter
import java.security.KeyPairGenerator
import kotlin.test.assertIs
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.Json
import no.nav.checks.CheckResult
import no.nav.checks.Checks
import no.nav.datastore.FakeDatastore
import no.nav.metrics.TPTMetrics
import no.nav.tpt.FakeTptBackend
import no.nav.tpt.TptRequestHandler
import org.apache.logging.log4j.Level
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
class GitHubExpectedResponsesTest {
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
        private const val noAnalysesResponse = """
            {
              "message":"no analysis found",
              "documentation_url":"https://docs.github.com/rest/code-scanning/code-scanning#list-code-scanning-analyses-for-a-repository",
              "status":"404"
            }
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

        @JvmStatic
        fun codeScanningNotFoundResponses(): List<String> = listOf(
            noAnalysesResponse,
            """{"message":"Not Found"}""",
            "{}",
            "not-json"
        )
    }

    private data class Response(
        val body: String,
        val status: HttpStatusCode = HttpStatusCode.OK,
        val headers: Map<String, String> = emptyMap()
    )

    private suspend fun TestScope.withGitHub(
        responses: List<Response>,
        tokenResponse: Response = Response("""{"token":"test-token","expires_at":"2099-01-01T00:00:00Z"}"""),
        block: suspend (RealGitHub, List<String>) -> Unit
    ) {
        val paths = mutableListOf<String>()
        var apiRequest = 0
        val engine = MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                val path = request.url.encodedPath
                paths += path
                val response = if (path == "/app/installations/456/access_tokens") {
                    tokenResponse
                } else {
                    when {
                        path.endsWith("/dependabot/alerts") -> assertEquals("open", request.url.parameters["state"])
                        path.endsWith("/code-scanning/analyses") -> assertEquals("100", request.url.parameters["per_page"])
                        else -> error("Unexpected GitHub request path: $path")
                    }
                    responses[minOf(apiRequest++, responses.lastIndex)]
                }
                respond(response.body, response.status, Headers.build {
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    response.headers.forEach { (name, value) -> append(name, value) }
                })
            }
        })
        HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = true
        }.use { client ->
            block(RealGitHub(
                client, "123", "456", privateKeyPem,
                GitHubTransport(client, timeSource = testScheduler.timeSource, jitter = { it })
            ), paths)
        }
    }

    private fun failedChecks() = TPTMetrics.registry.find("webhooks_failed").counter()?.count() ?: 0.0
    private fun alertRequests(paths: List<String>) = paths.count { it.endsWith("/dependabot/alerts") }
    private fun analysisRequests(paths: List<String>) = paths.count { it.endsWith("/code-scanning/analyses") }

    @ParameterizedTest
    @MethodSource("codeScanningNotFoundResponses")
    fun `code scanning not found retains empty-result semantics without retries or warnings`(body: String) = runTest {
        withCapturedLogs { events ->
            withGitHub(listOf(Response(body, HttpStatusCode.NotFound))) { gitHub, paths ->
                assertTrue(gitHub.latestCodeScanningAnalysesFor("repo").isEmpty())
                assertEquals(1, analysisRequests(paths))
                assertEquals(0L, currentTime)
                assertTrue(events.none { it.level.isMoreSpecificThan(Level.WARN) })
            }
        }
    }

    @Test
    fun `successful empty code scanning analyses remain empty`() = runTest {
        withGitHub(listOf(Response("[]"))) { gitHub, paths ->
            assertTrue(gitHub.latestCodeScanningAnalysesFor("repo").isEmpty())
            assertEquals(1, analysisRequests(paths))
        }
    }

    @Test
    fun `successful code scanning responses retain the newest analysis per category`() = runTest {
        val old = GithubCodeScanningAnalysis(
            "workflow-a", CodeScanningTool("CodeQL"), "", Instant.parse("2026-11-01T00:00:00Z"),
            "https://api.github.com/repos/navikt/repo/code-scanning/analyses/1"
        )
        val newest = old.copy(
            createdAt = Instant.parse("2026-11-02T00:00:00Z"),
            url = "https://api.github.com/repos/navikt/repo/code-scanning/analyses/2"
        )
        val anotherCategory = old.copy(
            category = "workflow-b",
            url = "https://api.github.com/repos/navikt/repo/code-scanning/analyses/3"
        )
        withGitHub(listOf(Response(Json.encodeToString(listOf(old, newest, anotherCategory))))) { gitHub, paths ->
            assertEquals(listOf(newest, anotherCategory), gitHub.latestCodeScanningAnalysesFor("repo"))
            assertEquals(1, analysisRequests(paths))
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403, 429, 500])
    fun `code scanning failures use selective retries`(status: Int) = runTest {
        withGitHub(listOf(Response(noAnalysesResponse, HttpStatusCode.fromValue(status)))) { gitHub, paths ->
            val failure = assertThrows<GitHubTransportException> { gitHub.latestCodeScanningAnalysesFor("repo") }
            assertEquals(HttpStatusCode.fromValue(status), failure.response?.status)
            assertEquals(if (status == 500) 3 else 1, analysisRequests(paths))
            assertEquals(if (status == 500) 3000L else 0L, currentTime)
        }
    }

    @Test
    fun `code scanning transient failure followed by not found stops at the expected response`() = runTest {
        withGitHub(listOf(
            Response("""{"message":"Unavailable"}""", HttpStatusCode.ServiceUnavailable),
            Response(noAnalysesResponse, HttpStatusCode.NotFound)
        )) { gitHub, paths ->
            assertTrue(gitHub.latestCodeScanningAnalysesFor("repo").isEmpty())
            assertEquals(2, analysisRequests(paths))
            assertEquals(1000L, currentTime)
        }
    }

    @Test
    fun `token exchange not found is not converted into empty code scanning analyses`() = runTest {
        withGitHub(
            responses = emptyList(),
            tokenResponse = Response(noAnalysesResponse, HttpStatusCode.NotFound)
        ) { gitHub, paths ->
            assertThrows<GitHubTransportException> { gitHub.latestCodeScanningAnalysesFor("repo") }
            assertEquals(0, analysisRequests(paths))
            assertEquals(1, paths.size)
        }
    }

    @Test
    fun `missing code scanning analyses still publish the tooling finding without a failed check`() = runTest {
        withCapturedLogs { events ->
            withGitHub(listOf(Response(noAnalysesResponse, HttpStatusCode.NotFound))) { realGitHub, paths ->
                val backend = FakeTptBackend()
                val gitHub = object : FakeGitHub() {
                    override suspend fun allReposForTeam(teamName: String) = listOf("repo")
                    override suspend fun latestCodeScanningAnalysesFor(repoName: String) =
                        realGitHub.latestCodeScanningAnalysesFor(repoName)
                }
                val before = failedChecks()

                TptRequestHandler(gitHub, Checks(gitHub, FakeDatastore()), backend).runAllChecksFor("team")

                assertEquals(before, failedChecks())
                assertEquals(1, analysisRequests(paths))
                val published = backend.sentCheckResults.single()
                assertEquals("repo", published.repoName)
                val finding = assertIs<CheckResult.NeedsWork>(
                    published.results.single { it.name == "GitHubToolingStatus" }
                )
                assertEquals(listOf("repo has no code scanning analyses, possibly no tools configured"), finding.reasons)
                assertTrue(published.results.any { it.name == "HasCriticalVulns" })
                assertTrue(events.none { it.level.isMoreSpecificThan(Level.WARN) })
            }
        }
    }

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
    fun `other forbidden responses fail without blind retries`(body: String) = runTest {
        withGitHub(listOf(Response(body, HttpStatusCode.Forbidden))) { gitHub, paths ->
            val failure = assertThrows<GitHubTransportException> { gitHub.dependabotOpenSecurityAlertsFor("repo") }
            assertEquals(HttpStatusCode.Forbidden, failure.response?.status)
            assertEquals(1, alertRequests(paths))
            assertEquals(0L, currentTime)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["Retry-After", "X-RateLimit-Remaining"])
    fun `rate limit headers take precedence over a disabled message`(header: String) = runTest {
        val value = if (header == "Retry-After") "60" else "0"
        withGitHub(listOf(Response(disabledResponse, HttpStatusCode.Forbidden, mapOf(header to value)))) { gitHub, paths ->
            val failure = assertThrows<GitHubTransportException> { gitHub.dependabotOpenSecurityAlertsFor("repo") }
            assertEquals(HttpStatusCode.Forbidden, failure.response?.status)
            assertEquals(1, alertRequests(paths))
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 429, 500])
    fun `a disabled message on another status is not a disabled result`(status: Int) = runTest {
        withGitHub(listOf(Response(disabledResponse, HttpStatusCode.fromValue(status)))) { gitHub, paths ->
            val failure = assertThrows<GitHubTransportException> { gitHub.dependabotOpenSecurityAlertsFor("repo") }
            assertEquals(HttpStatusCode.fromValue(status), failure.response?.status)
            assertEquals(if (status == 500) 3 else 1, alertRequests(paths))
        }
    }

    @Test
    fun `Dependabot not found remains an empty success without retrying`() = runTest {
        withGitHub(listOf(Response("""{"message":"Not Found"}""", HttpStatusCode.NotFound))) { gitHub, paths ->
            assertEquals(DependabotAlertsResult.Available(emptyMap()), gitHub.dependabotOpenSecurityAlertsFor("repo"))
            assertEquals(1, alertRequests(paths))
            assertEquals(0L, currentTime)
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
            assertThrows<GitHubTransportException> { gitHub.dependabotOpenSecurityAlertsFor("repo") }
            assertEquals(0, alertRequests(paths))
            assertEquals(1, paths.size)
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
