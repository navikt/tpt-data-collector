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
import io.ktor.client.request.HttpRequestData
import io.ktor.serialization.kotlinx.json.json
import java.io.StringWriter
import java.security.KeyPairGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import no.nav.checks.Checks
import no.nav.datastore.FakeDatastore
import no.nav.tpt.FakeTptBackend
import no.nav.tpt.TptRequestHandler

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubTeamReposPaginationTest {
    private val base = "https://api.github.com/orgs/navikt/teams/big-team/repos"

    private val privateKeyPem by lazy {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        StringWriter().also { writer -> JcaPEMWriter(writer).use { it.writeObject(keyPair) } }.toString()
    }

    private fun reposJson(from: Int, to: Int, archived: Set<Int> = emptySet()) =
        (from..to).joinToString(",", "[", "]") { """{"name":"repo-$it","archived":${it in archived}}""" }

    private data class Response(
        val body: String = "[]",
        val links: List<String> = emptyList(),
        val status: HttpStatusCode = HttpStatusCode.OK
    )

    private suspend fun TestScope.withGitHub(
        responseFor: suspend (HttpRequestData) -> Response,
        block: suspend (RealGitHub, List<String>) -> Unit
    ) {
        val requestedUrls = mutableListOf<String>()
        val engine = MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                val response = if (request.url.encodedPath.endsWith("/access_tokens")) {
                    Response("""{"token":"t","expires_at":"2099-01-01T00:00:00Z"}""")
                } else {
                    requestedUrls += request.url.toString()
                    responseFor(request)
                }
                respond(response.body, response.status, Headers.build {
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    response.links.forEach { append(HttpHeaders.Link, it) }
                })
            }
        })
        HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = true
        }.use { client ->
            val transport = GitHubTransport(client, timeSource = testScheduler.timeSource, jitter = { it })
            block(RealGitHub(client, "123", "456", privateKeyPem, "navikt", transport), requestedUrls)
        }
    }

    @Test
    fun `fetches every page of a team's repositories`() = runTest {
        withGitHub(responseFor = { request ->
            when (request.url.parameters["page"]) {
                null -> Response(
                    reposJson(1, 100, archived = setOf(7)),
                    listOf("<$base?per_page=100&page=2>; rel=\"next\", <$base?per_page=100&page=3>; rel=\"last\"")
                )
                "2" -> Response(
                    reposJson(101, 200),
                    listOf("<$base?per_page=100&page=1>; rel=\"prev\", <$base?per_page=100&page=3>; rel=\"next\"")
                )
                "3" -> Response(
                    reposJson(201, 230),
                    listOf("<$base?per_page=100&page=2>; rel=\"prev\", <$base?per_page=100&page=1>; rel=\"first\"")
                )
                else -> error("Unexpected page")
            }
        }) { gitHub, requestedUrls ->
            val repos = gitHub.allReposForTeam("big-team")
            assertEquals((1..230).filter { it != 7 }.map { "repo-$it" }, repos)
            assertEquals(
                listOf("$base?per_page=100", "$base?per_page=100&page=2", "$base?per_page=100&page=3"),
                requestedUrls
            )
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 3])
    fun `empty and single pages finish without another request`(count: Int) = runTest {
        withGitHub(responseFor = { Response(reposJson(1, count)) }) { gitHub, urls ->
            assertEquals((1..count).map { "repo-$it" }, gitHub.allReposForTeam("big-team"))
            assertEquals(1, urls.size)
        }
    }

    @Test
    fun `finds the next page url in a link header`() {
        assertEquals(
            "https://api.github.com/x?page=2",
            nextPageUrl("<https://api.github.com/x?page=1>; rel=\"prev\", <https://api.github.com/x?page=2>; rel=\"next\"")
        )
        assertNull(nextPageUrl("<https://api.github.com/x?page=1>; rel=\"first\""))
        assertNull(nextPageUrl(null))
    }

    @Test
    fun `parses commas in URLs and quoted parameters and multiple relation names`() {
        assertEquals(
            "$base?cursor=a,b",
            nextPageUrl("""<$base?cursor=a,b>; title="a,b;c"; rel="prev next"""")
        )
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "", "not-a-link", "https://api.github.com/x?page=2; rel=\"next\"",
        "<https://api.github.com/x?page=2; rel=\"next\"",
        "<https://api.github.com/x>; rel=\"next",
        "<https://api.github.com/x>; rel",
        "<https://api.github.com/x>; rel=\"next\"; rel=\"prev\"",
        "<https://api.github.com/x>; rel=\"next\",",
        "<https://api.github.com/x>; rel=\"next\", garbage"
    ])
    fun `malformed links fail instead of completing a partial collection`(link: String) = runTest {
        withGitHub(responseFor = { Response(reposJson(1, 1), listOf(link)) }) { gitHub, urls ->
            assertThrows<GitHubPaginationException> { gitHub.allReposForTeam("big-team") }
            assertEquals(1, urls.size)
        }
    }

    @Test
    fun `multiple next links across separate headers fail`() = runTest {
        withGitHub(responseFor = {
            Response(links = listOf("<$base?page=2>; rel=\"next\"", "<$base?page=3>; rel=\"next\""))
        }) { gitHub, urls ->
            assertThrows<GitHubPaginationException> { gitHub.allReposForTeam("big-team") }
            assertEquals(1, urls.size)
        }
    }

    @Test
    fun `next relation is found across separate Link headers`() = runTest {
        withGitHub(responseFor = { request ->
            if (request.url.parameters["page"] == null) {
                Response(reposJson(1, 1), listOf(
                    "<$base?page=1>; rel=\"first\"",
                    "<$base?page=2>; rel=\"next\""
                ))
            } else {
                Response(reposJson(2, 2))
            }
        }) { gitHub, urls ->
            assertEquals(listOf("repo-1", "repo-2"), gitHub.allReposForTeam("big-team"))
            assertEquals(2, urls.size)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "https://example.com/repos", "https://api.github.com.example.com/repos",
        "http://api.github.com/repos", "https://api.github.com:8443/repos",
        "https://user@api.github.com/repos", "https://api.github.com/repos#fragment",
        "/repos?page=2", "//api.github.com/repos", "https://api.github.com/repos?cursor=%zz"
    ])
    fun `invalid next URLs are rejected before any authorized request to them`(url: String) = runTest {
        withGitHub(responseFor = { Response(links = listOf("<$url>; rel=\"next\"")) }) { gitHub, urls ->
            assertThrows<GitHubPaginationException> { gitHub.allReposForTeam("big-team") }
            assertEquals(listOf("$base?per_page=100"), urls)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `repeated pages and cycles fail before revisiting a page`(cycle: Boolean) = runTest {
        withGitHub(responseFor = { request ->
            val next = if (cycle && request.url.parameters["page"] == "2") "$base?per_page=100" else "$base?page=2"
            Response(reposJson(1, 1), listOf("<$next>; rel=\"next\""))
        }) { gitHub, urls ->
            assertThrows<GitHubPaginationException> { gitHub.allReposForTeam("big-team") }
            assertEquals(listOf("$base?per_page=100", "$base?page=2"), urls)
        }
    }

    @Test
    fun `cycle identity normalizes default port host casing and dot segments`() {
        val pagination = GitHubPagination()
        pagination.visit("$base?page=2")
        assertThrows<GitHubPaginationException> {
            pagination.visit("https://API.GITHUB.COM:443/orgs/navikt/teams/big-team/./repos?page=2")
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [200, 403, 500])
    fun `later page failure does not publish earlier repositories`(status: Int) = runTest {
        withGitHub(responseFor = { request ->
            if (request.url.parameters["page"] == null) {
                Response(reposJson(1, 1), listOf("<$base?page=2>; rel=\"next\""))
            } else {
                Response("not-json", status = HttpStatusCode.fromValue(status))
            }
        }) { gitHub, urls ->
            val backend = FakeTptBackend()
            val handler = TptRequestHandler(gitHub, Checks(gitHub, FakeDatastore()), backend)
            assertThrows<GitHubTransportException> { handler.runAllChecksFor("big-team") }
            assertTrue(backend.sentCheckResults.isEmpty())
            assertEquals(if (status == 500) 4 else 2, urls.size)
        }
    }

    @Test
    fun `unique advancing links are bounded by the overall collection deadline`() = runTest {
        var page = 1
        withGitHub(responseFor = {
            delay(1000)
            Response(reposJson(1, 1), listOf("<$base?page=${++page}>; rel=\"next\""))
        }) { gitHub, _ ->
            val failure = assertThrows<GitHubPaginationException> { gitHub.allReposForTeam("big-team") }
            assertTrue(failure.message.orEmpty().contains("time budget exceeded"))
            assertEquals(60_000L, currentTime)
        }
    }

    @Test
    fun `caller deadline propagates instead of becoming a pagination failure`() = runTest {
        withGitHub(responseFor = { awaitCancellation() }) { gitHub, _ ->
            assertThrows<TimeoutCancellationException> {
                withTimeout(1000) { gitHub.allReposForTeam("big-team") }
            }
            assertEquals(1000L, currentTime)
        }
    }

    @Test
    fun `cancellation stops collection without publishing partial data`() = runTest {
        val secondPageStarted = CompletableDeferred<Unit>()
        withGitHub(responseFor = { request ->
            if (request.url.parameters["page"] == null) {
                Response(reposJson(1, 1), listOf("<$base?page=2>; rel=\"next\""))
            } else {
                secondPageStarted.complete(Unit)
                awaitCancellation()
            }
        }) { gitHub, urls ->
            val backend = FakeTptBackend()
            val handler = TptRequestHandler(gitHub, Checks(gitHub, FakeDatastore()), backend)
            val collection = async { handler.runAllChecksFor("big-team") }
            secondPageStarted.await()
            collection.cancelAndJoin()
            assertTrue(collection.isCancelled)
            assertTrue(backend.sentCheckResults.isEmpty())
            assertEquals(2, urls.size)
        }
    }
}
