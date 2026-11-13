package no.nav.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import java.io.StringWriter
import java.security.KeyPairGenerator
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import no.nav.tpt.FakeTptBackend
import no.nav.whodis.FakeWhodis
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource

@OptIn(ExperimentalCoroutinesApi::class)
internal class GitHubTransportIntegrationTest {
    companion object {
        private val privateKeyPem by lazy {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            StringWriter().also { writer -> JcaPEMWriter(writer).use { it.writeObject(keyPair) } }.toString()
        }
        private const val tokenPath = "/app/installations/456/access_tokens"
        private const val repoPath = "/repos/navikt/repo"
        private const val teamPath = "/orgs/navikt/teams/team/repos"
        private const val graphPage = """
            {"data":{"repository":{"vulnerabilityAlerts":{
              "nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}
            }}}}
        """
        private const val graphRateLimit = """
            {"errors":[{"type":"RATE_LIMITED","message":"private upstream details"}]}
        """
        private val firstGraphPage = graphPage
            .replace("false", "true").replace("null", "\"next\"")
            .replace(
                "\"nodes\":[]",
                """"nodes":[{"dependencyScope":"RUNTIME","dependabotUpdate":null,"securityAdvisory":null,
                    "securityVulnerability":{"severity":"HIGH","package":{"ecosystem":"MAVEN","name":"example"}}}]"""
            )
    }

    private data class Response(val body: String, val status: Int = 200, val headers: Map<String, String> = emptyMap())
    private data class Request(val key: String, val method: HttpMethod, val body: String)

    private fun successfulResponses() = mapOf(
        tokenPath to listOf(Response("""{"token":"installation-token","expires_at":"2099-01-01T00:00:00Z"}""", 201)),
        repoPath to listOf(Response("""{"default_branch":"main"}""")),
        "$repoPath/git/trees/main" to listOf(Response("""{"sha":"${"1".repeat(40)}","truncated":false,"tree":[]}""")),
        "$repoPath/contents/file" to listOf(Response("""{"content":"aGVsbG8="}""")),
        "$repoPath/dependabot/alerts" to listOf(Response("[]")),
        "$repoPath/code-scanning/analyses" to listOf(Response("[]")),
        "$repoPath/commits" to listOf(Response("""[{"commit":{"author":{"date":"2026-11-11T00:00:00Z"}}}]""")),
        teamPath to listOf(Response("""[{"name":"repo"}]""")),
        "/graphql" to listOf(Response(graphPage))
    )

    private suspend fun TestScope.withGitHub(
        responses: Map<String, List<Response>> = successfulResponses(),
        organization: String = "navikt",
        block: suspend (RealGitHub, List<Request>) -> Unit
    ) {
        val requests = mutableListOf<Request>()
        val engine = MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                val page = request.url.parameters["page"]
                val key = request.url.encodedPath + if (page == null) "" else "?page=$page"
                val index = requests.count { it.key == key }
                val options = responses.getValue(key)
                val response = options[minOf(index, options.lastIndex)]
                requests += Request(
                    key, request.method,
                    if (request.url.encodedPath == "/graphql") request.body.toByteArray().decodeToString() else ""
                )
                respond(response.body, HttpStatusCode.fromValue(response.status), Headers.build {
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    response.headers.forEach { (name, value) -> append(name, value) }
                })
            }
        })
        HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout)
            expectSuccess = true
        }.use { client ->
            val transport = GitHubTransport(
                client, timeSource = testScheduler.timeSource,
                now = { Instant.parse("2026-11-11T12:00:00Z") + currentTime.milliseconds },
                jitter = { it }
            )
            block(RealGitHub(client, "123", "456", privateKeyPem, organization, transport), requests)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["navikt", "another-org"])
    fun `all organization scoped requests use the configured organization`(organization: String) = runTest {
        val responses = successfulResponses().mapKeys { (path, _) ->
            path.replace("/navikt/", "/$organization/")
        }
        withGitHub(responses, organization) { gitHub, requests ->
            assertEquals("hello", gitHub.readFileContents("repo", "file"))
            assertEquals(DependabotAlertsResult.Available(false), gitHub.hasOpenCriticalDependabotAlerts("repo"))
            assertTrue(gitHub.latestCodeScanningAnalysesFor("repo").isEmpty())
            assertTrue(gitHub.allFilePathsIn("repo").isEmpty())
            assertEquals(listOf("repo"), gitHub.allReposForTeam("team"))
            assertEquals(Instant.parse("2026-11-11T00:00:00Z"), gitHub.latestCommitTimeFor("repo"))
            assertEquals(
                listOf(
                    tokenPath,
                    "/repos/$organization/repo/contents/file",
                    "/repos/$organization/repo/dependabot/alerts",
                    "/repos/$organization/repo/code-scanning/analyses",
                    "/repos/$organization/repo",
                    "/repos/$organization/repo/git/trees/main",
                    "/orgs/$organization/teams/team/repos",
                    "/repos/$organization/repo/commits",
                ),
                requests.map { it.key },
            )
        }
    }

    @ParameterizedTest
    @EnumSource(GitHubOperation::class)
    fun `every GitHub request path uses the shared retry policy`(operation: GitHubOperation) = runTest {
        val path = when (operation) {
            GitHubOperation.FILE_CONTENTS -> "$repoPath/contents/file"
            GitHubOperation.DEPENDABOT_ALERTS -> "$repoPath/dependabot/alerts"
            GitHubOperation.CODE_SCANNING -> "$repoPath/code-scanning/analyses"
            GitHubOperation.REPOSITORY -> repoPath
            GitHubOperation.TREE -> "$repoPath/git/trees/main"
            GitHubOperation.TEAM_REPOSITORIES -> teamPath
            GitHubOperation.VULNERABILITY_ALERTS -> "/graphql"
            GitHubOperation.COMMITS -> "$repoPath/commits"
            GitHubOperation.INSTALLATION_TOKEN -> tokenPath
        }
        val responses = successfulResponses().toMutableMap()
        responses[path] = listOf(Response("""{"message":"Unavailable"}""", 503)) + responses.getValue(path)
        withGitHub(responses) { gitHub, requests ->
            when (operation) {
                GitHubOperation.FILE_CONTENTS -> assertEquals("hello", gitHub.readFileContents("repo", "file"))
                GitHubOperation.DEPENDABOT_ALERTS ->
                    assertEquals(DependabotAlertsResult.Available(false), gitHub.hasOpenCriticalDependabotAlerts("repo"))
                GitHubOperation.CODE_SCANNING -> assertTrue(gitHub.latestCodeScanningAnalysesFor("repo").isEmpty())
                GitHubOperation.REPOSITORY, GitHubOperation.TREE -> assertTrue(gitHub.allFilePathsIn("repo").isEmpty())
                GitHubOperation.TEAM_REPOSITORIES, GitHubOperation.INSTALLATION_TOKEN ->
                    assertEquals(listOf("repo"), gitHub.allReposForTeam("team"))
                GitHubOperation.VULNERABILITY_ALERTS -> assertTrue(gitHub.vulnerabilityAlertsFor("navikt", "repo").isEmpty())
                GitHubOperation.COMMITS -> assertEquals(
                    Instant.parse("2026-11-11T00:00:00Z"), gitHub.latestCommitTimeFor("repo")
                )
            }
            val retried = requests.filter { it.key == path }
            assertEquals(2, retried.size)
            assertEquals(1000L, currentTime)
            if (operation == GitHubOperation.VULNERABILITY_ALERTS) {
                assertTrue(retried.all { it.method == HttpMethod.Post })
                assertEquals(retried[0].body, retried[1].body)
            }
            if (operation == GitHubOperation.INSTALLATION_TOKEN) {
                assertTrue(retried.all { it.method == HttpMethod.Post })
            }
        }
    }

    @Test
    fun `later team pages retry independently and retain Link headers`() = runTest {
        val responses = successfulResponses() + mapOf(
            teamPath to listOf(Response("""[{"name":"first"}]""", headers = mapOf(
                HttpHeaders.Link to "<https://api.github.com$teamPath?per_page=100&page=2>; rel=\"next\""
            ))),
            "$teamPath?page=2" to listOf(Response("{}", 503), Response("""[{"name":"second"}]"""))
        )
        withGitHub(responses) { gitHub, requests ->
            assertEquals(listOf("first", "second"), gitHub.allReposForTeam("team"))
            assertEquals(listOf(tokenPath, teamPath, "$teamPath?page=2", "$teamPath?page=2"), requests.map { it.key })
        }
    }

    @Test
    fun `later GraphQL pages retry the same cursor without duplicating earlier data`() = runTest {
        val firstPage = graphPage.replace("false", "true").replace("null", "\"next\"")
        val responses = successfulResponses() + ("/graphql" to listOf(
            Response(firstPage), Response("{}", 503), Response(graphPage)
        ))
        withGitHub(responses) { gitHub, requests ->
            assertTrue(gitHub.vulnerabilityAlertsFor("navikt", "repo").isEmpty())
            val graphRequests = requests.filter { it.key == "/graphql" }
            assertEquals(3, graphRequests.size)
            assertEquals(graphRequests[1].body, graphRequests[2].body)
            assertTrue(graphRequests[1].body.contains("\"vulnEndCursor\":\"next\""))
            assertEquals(1000L, currentTime)
        }
    }

    private fun rateLimitHeaders(primary: Boolean, wait: String): Map<String, String> =
        if (primary) mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to wait)
        else mapOf("Retry-After" to wait)

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `GraphQL HTTP 200 rate limits recover through the shared policy`(primary: Boolean) = runTest {
        val wait = if (primary) (Instant.parse("2026-11-11T12:00:00Z").epochSeconds + 2).toString() else "2"
        val responses = successfulResponses() + ("/graphql" to listOf(
            Response(graphRateLimit, headers = rateLimitHeaders(primary, wait) +
                ("X-GitHub-Request-Id" to "ABCD:1234")),
            Response(graphPage)
        ))
        withCapturedLogs { events ->
            withGitHub(responses) { gitHub, requests ->
                assertTrue(gitHub.vulnerabilityAlertsFor("navikt", "repo").isEmpty())
                val graphRequests = requests.filter { it.key == "/graphql" }
                assertEquals(2, graphRequests.size)
                assertEquals(graphRequests[0].body, graphRequests[1].body)
                assertEquals(2000L, currentTime)
                val event = events.single()
                assertEquals("GitHub request failed; retrying", event.message.formattedMessage)
                assertEquals("vulnerability_alerts", event.contextData.getValue<String>("operation"))
                assertEquals("navikt/repo", event.contextData.getValue<String>("context"))
                assertEquals("200", event.contextData.getValue<String>("status"))
                assertEquals("ABCD:1234", event.contextData.getValue<String>("request_id"))
                assertEquals("1", event.contextData.getValue<String>("attempt"))
                assertEquals(if (primary) "primary_rate_limit" else "secondary_rate_limit",
                    event.contextData.getValue<String>("failure"))
                assertEquals("2000", event.contextData.getValue<String>("delay_ms"))
                assertFalse(event.contextData.toMap().toString().contains("private upstream details"))
                assertNull(event.thrown)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `GraphQL HTTP 200 rate limits exhaust the shared attempt budget`(primary: Boolean) = runTest {
        val wait = if (primary) Instant.parse("2026-11-11T12:00:00Z").epochSeconds.toString() else "0"
        val responses = successfulResponses() + ("/graphql" to listOf(
            Response(graphRateLimit, headers = rateLimitHeaders(primary, wait) +
                ("X-GitHub-Request-Id" to "ABCD:1234"))
        ))
        withGitHub(responses) { gitHub, requests ->
            val failure = assertThrows<GitHubTransportException> {
                gitHub.vulnerabilityAlertsFor("navikt", "repo")
            }
            assertEquals(
                if (primary) GitHubFailure.PRIMARY_RATE_LIMIT else GitHubFailure.SECONDARY_RATE_LIMIT,
                failure.failure
            )
            assertEquals(GitHubStopReason.ATTEMPTS_EXHAUSTED, failure.stopReason)
            assertEquals(3, failure.attempt)
            assertEquals(200, failure.response?.status?.value)
            assertEquals("ABCD:1234", failure.response?.headers?.get("X-GitHub-Request-Id"))
            assertEquals(3, requests.count { it.key == "/graphql" })
            assertEquals(3000L, currentTime)
            assertFalse(failure.stackTraceToString().contains("private upstream details"))
            assertNull(failure.cause)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `GraphQL rate limits reject malformed and excessive waits`(primary: Boolean) = runTest {
        val excessive = if (primary) (Instant.parse("2026-11-11T12:00:00Z").epochSeconds + 60).toString() else "60"
        for ((wait, reason) in listOf(
            "invalid" to GitHubStopReason.INVALID_RATE_LIMIT_WAIT,
            excessive to GitHubStopReason.TIME_BUDGET
        )) {
            val responses = successfulResponses() + ("/graphql" to listOf(
                Response(graphRateLimit, headers = rateLimitHeaders(primary, wait))
            ))
            withGitHub(responses) { gitHub, requests ->
                val failure = assertThrows<GitHubTransportException> {
                    gitHub.vulnerabilityAlertsFor("navikt", "repo")
                }
                assertEquals(reason, failure.stopReason)
                assertEquals(1, requests.count { it.key == "/graphql" })
                assertEquals(0L, currentTime)
            }
        }
    }

    @Test
    fun `GraphQL rate limits without timing headers cannot use short backoff`() = runTest {
        withGitHub(successfulResponses() + ("/graphql" to listOf(Response(graphRateLimit)))) { gitHub, requests ->
            val failure = assertThrows<GitHubTransportException> {
                gitHub.vulnerabilityAlertsFor("navikt", "repo")
            }
            assertEquals(GitHubFailure.SECONDARY_RATE_LIMIT, failure.failure)
            assertEquals(GitHubStopReason.TIME_BUDGET, failure.stopReason)
            assertEquals(1, requests.count { it.key == "/graphql" })
            assertEquals(0L, currentTime)
        }
    }

    @Test
    fun `GraphQL rate limit waits share the remaining elapsed budget`() = runTest {
        val responses = successfulResponses() + ("/graphql" to listOf(
            Response(graphRateLimit, headers = mapOf("Retry-After" to "19")),
            Response(graphRateLimit, headers = mapOf("Retry-After" to "2"))
        ))
        withGitHub(responses) { gitHub, requests ->
            val failure = assertThrows<GitHubTransportException> {
                gitHub.vulnerabilityAlertsFor("navikt", "repo")
            }
            assertEquals(GitHubStopReason.TIME_BUDGET, failure.stopReason)
            assertEquals(2, failure.attempt)
            assertEquals(2, requests.count { it.key == "/graphql" })
            assertEquals(19000L, currentTime)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["API rate limit exceeded", "You have exceeded a secondary rate limit.", "abuse detection"])
    fun `GraphQL rate limit messages are recognized without an error type`(message: String) = runTest {
        val responses = successfulResponses() + ("/graphql" to listOf(
            Response("""{"errors":[{"message":"$message"}]}""", headers = mapOf("Retry-After" to "2")),
            Response(graphPage)
        ))
        withGitHub(responses) { gitHub, requests ->
            assertTrue(gitHub.vulnerabilityAlertsFor("navikt", "repo").isEmpty())
            assertEquals(2, requests.count { it.key == "/graphql" })
            assertEquals(2000L, currentTime)
        }
    }

    @Test
    fun `other and mixed GraphQL errors remain permanent even with rate limit headers`() = runTest {
        for (errors in listOf(
            """{"message":"Resource not accessible by integration","type":"FORBIDDEN"}""",
            """{"message":"Resource not accessible by integration","type":"FORBIDDEN"},
               {"message":"API rate limit exceeded","type":"RATE_LIMITED"}"""
        )) {
            val responses = successfulResponses() + ("/graphql" to listOf(
                Response("""{"errors":[$errors]}""", headers = mapOf(
                    "X-RateLimit-Remaining" to "0", "Retry-After" to "2"
                ))
            ))
            withCapturedLogs { events ->
                withGitHub(responses) { gitHub, requests ->
                    assertThrows<GitHubVulnerabilityCollectionException> {
                        gitHub.vulnerabilityAlertsFor("navikt", "repo")
                    }
                    assertEquals(1, requests.count { it.key == "/graphql" })
                    assertEquals(0L, currentTime)
                    assertTrue(events.isEmpty())
                }
            }
        }
    }

    @Test
    fun `successful GraphQL data is not retried when it consumes the last rate limit point`() = runTest {
        val responses = successfulResponses() + ("/graphql" to listOf(
            Response(graphPage, headers = mapOf("X-RateLimit-Remaining" to "0"))
        ))
        withGitHub(responses) { gitHub, requests ->
            assertTrue(gitHub.vulnerabilityAlertsFor("navikt", "repo").isEmpty())
            assertEquals(1, requests.count { it.key == "/graphql" })
            assertEquals(0L, currentTime)
        }
    }

    @Test
    fun `later GraphQL HTTP 200 rate limits retry the same cursor`() = runTest {
        val responses = successfulResponses() + ("/graphql" to listOf(
            Response(firstGraphPage),
            Response(graphRateLimit, headers = mapOf("Retry-After" to "2")),
            Response(graphPage)
        ))
        withGitHub(responses) { gitHub, requests ->
            val alerts = gitHub.vulnerabilityAlertsFor("navikt", "repo")
            assertEquals(1, alerts.size)
            assertEquals("example", alerts.single().securityVulnerability?.pkg?.name)
            val graphRequests = requests.filter { it.key == "/graphql" }
            assertEquals(3, graphRequests.size)
            assertEquals(graphRequests[1].body, graphRequests[2].body)
            assertTrue(graphRequests[1].body.contains("\"vulnEndCursor\":\"next\""))
            assertEquals(2000L, currentTime)
        }
    }

    @Test
    fun `exhausted GraphQL rate limits never publish an earlier partial page`() = runTest {
        val responses = successfulResponses() + ("/graphql" to listOf(
            Response(firstGraphPage),
            Response(graphRateLimit, headers = mapOf("Retry-After" to "1"))
        ))
        withGitHub(responses) { gitHub, requests ->
            val backend = FakeTptBackend()
            GitHubCollectHandler(gitHub, FakeWhodis(), backend).collect(
                GitHubCollectRequest(repositories = listOf("navikt/repo"))
            )
            assertTrue(backend.sentVulnerabilityData.isEmpty())
            assertEquals(1, backend.sentSyncComplete.size)
            assertEquals(4, requests.count { it.key == "/graphql" })
            assertEquals(3000L, currentTime)
        }
    }

    @Test
    fun `inventory deadline also encloses transport rate limit waits`() = runTest {
        val responses = successfulResponses() + ("$repoPath/git/trees/main" to listOf(
            Response("{}", 429, mapOf("Retry-After" to "10"))
        ))
        withGitHub(responses) { gitHub, requests ->
            val failure = assertThrows<GitHubFileInventoryException> {
                gitHub.allFilePathsIn("repo", TreeInventoryLimits(timeout = 2.seconds))
            }
            assertTrue(failure.message!!.contains("time budget"))
            assertEquals(2000L, currentTime)
            assertEquals(1, requests.count { it.key == "$repoPath/git/trees/main" })
        }
    }

    @Test
    fun `empty repository remains an expected success without transport warnings`() = runTest {
        val responses = successfulResponses() + ("$repoPath/git/trees/main" to listOf(
            Response("""{"message":"Git Repository is empty."}""", 409)
        ))
        withCapturedLogs { events ->
            withGitHub(responses) { gitHub, requests ->
                assertTrue(gitHub.allFilePathsIn("repo").isEmpty())
                assertEquals(1, requests.count { it.key == "$repoPath/git/trees/main" })
                assertTrue(events.isEmpty())
                assertEquals(0L, currentTime)
            }
        }
    }
}
