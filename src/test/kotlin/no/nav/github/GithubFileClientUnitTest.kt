package no.nav.github

import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.Optional
import javax.net.ssl.SSLSession
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GithubRepositoryClientUnitTest {
    @Test
    fun `readFile normalizes path and decodes base64 content`() {
        val requests = mutableListOf<HttpRequest>()
        val client = GithubRepositoryContentsClient(
            GithubApiClient(
            githubToken = "token",
            userAgent = "test-agent",
            httpSender = HttpStringSender { request ->
                requests += request
                TestHttpResponse(
                    statusCodeValue = 200,
                    bodyValue = """
                        {
                          "type": "file",
                          "encoding": "base64",
                          "content": "${mimeBase64("FROM alpine:3.21\nRUN echo ok\n")}"
                        }
                    """.trimIndent(),
                    requestValue = request,
                )
            },
            )
        )

        val content = client.readFile("navikt", "demo", "./nested\\Dockerfile", "main")

        assertEquals("FROM alpine:3.21\nRUN echo ok\n", content)
        assertEquals("https://api.github.com/repos/navikt/demo/contents/nested/Dockerfile?ref=main", requests.single().uri().toString())
        assertEquals("test-agent", requests.single().headers().firstValue("User-Agent").orElseThrow())
    }

    @Test
    fun `readFile rejects traversal before any network call`() {
        var callCount = 0
        val client = GithubRepositoryContentsClient(
            GithubApiClient(
            githubToken = "token",
            httpSender = HttpStringSender {
                callCount += 1
                TestHttpResponse(200, "{}", requestValue = it)
            },
            )
        )

        val exception = assertFailsWith<GithubRequestException> {
            client.readFile("navikt", "demo", "../Dockerfile", "main")
        }

        assertEquals(GithubRequestErrorKind.PERMANENT, exception.kind)
        assertEquals(0, callCount)
    }

    @Test
    fun `readFile classifies directory payload as permanent not-file failure`() {
        val client = GithubRepositoryContentsClient(
            GithubApiClient(
            githubToken = "token",
            httpSender = HttpStringSender { request ->
                TestHttpResponse(
                    statusCodeValue = 200,
                    bodyValue = """[{"type":"file"}]""",
                    requestValue = request,
                )
            },
            )
        )

        val exception = assertFailsWith<GithubRequestException> {
            client.readFile("navikt", "demo", "Dockerfile", "main")
        }

        assertEquals(GithubRequestErrorKind.PERMANENT, exception.kind)
        assertContains(exception.message ?: "", "directory")
    }

    @Test
    fun `readFile retries server failure before succeeding`() {
        val sleepCalls = mutableListOf<Duration>()
        var attempts = 0
        val client = GithubRepositoryContentsClient(
            GithubApiClient(
            githubToken = "token",
            httpSender = HttpStringSender { request ->
                attempts += 1
                if (attempts == 1) {
                    TestHttpResponse(500, """{"message":"bad gateway"}""", requestValue = request)
                } else {
                    TestHttpResponse(
                        200,
                        """{"type":"file","encoding":"base64","content":"${mimeBase64("FROM eclipse-temurin:21\n")}"}""",
                        requestValue = request,
                    )
                }
            },
            sleeper = Sleeper { duration -> sleepCalls += duration },
            )
        )

        val content = client.readFile("navikt", "demo", "Dockerfile", "main")

        assertEquals("FROM eclipse-temurin:21\n", content)
        assertEquals(2, attempts)
        assertEquals(listOf(Duration.ofSeconds(1)), sleepCalls)
    }

    @Test
    fun `readFile waits on retry-after rate limit before retrying`() {
        val clock = MutableClock(Instant.parse("2026-06-10T10:00:00Z"))
        val sleepCalls = mutableListOf<Duration>()
        var attempts = 0
        val client = GithubRepositoryContentsClient(
            GithubApiClient(
            githubToken = "token",
            clock = clock,
            httpSender = HttpStringSender { request ->
                attempts += 1
                if (attempts == 1) {
                    TestHttpResponse(
                        403,
                        """{"message":"secondary rate limit"}""",
                        headersMap = mapOf("Retry-After" to listOf("2")),
                        requestValue = request,
                    )
                } else {
                    TestHttpResponse(
                        200,
                        """{"type":"file","encoding":"base64","content":"${mimeBase64("FROM ghcr.io/navikt/baseimages/temurin:21\n")}"}""",
                        requestValue = request,
                    )
                }
            },
            sleeper = Sleeper { duration ->
                sleepCalls += duration
                clock.advance(duration)
            },
            )
        )

        val content = client.readFile("navikt", "demo", "Dockerfile", "main")

        assertEquals("FROM ghcr.io/navikt/baseimages/temurin:21\n", content)
        assertEquals(2, attempts)
        assertEquals(listOf(Duration.ofSeconds(2)), sleepCalls)
    }

    @Test
    fun `listBlobPaths returns blob paths from repository tree`() {
        val client = GithubGitTreeClient(
            GithubApiClient(
            githubToken = "token",
            httpSender = HttpStringSender { request ->
                TestHttpResponse(
                    200,
                    """
                        {
                          "truncated": false,
                          "tree": [
                            {"path":"Dockerfile","type":"blob"},
                            {"path":"DockerfileFeaturesTest.kt","type":"blob"},
                            {"path":"docker/service.Dockerfile","type":"blob"},
                            {"path":"docs/readme.md","type":"blob"},
                            {"path":"nested/CustomDockerfile","type":"blob"},
                            {"path":"submodule","type":"commit"},
                            {"path":"images/dockerfile.png","type":"blob"}
                          ]
                        }
                    """.trimIndent(),
                    requestValue = request,
                )
            },
            )
        )

        val candidates = client.listBlobPaths("navikt", "demo", "abcdef123456")

        assertEquals(
            setOf(
            "Dockerfile",
            "DockerfileFeaturesTest.kt",
            "docker/service.Dockerfile",
            "docs/readme.md",
            "nested/CustomDockerfile",
            "images/dockerfile.png",
            ),
            candidates,
        )
    }

    @Test
    fun `listBlobPaths fails on truncated tree`() {
        val client = GithubGitTreeClient(
            GithubApiClient(
            githubToken = "token",
            httpSender = HttpStringSender { request ->
                TestHttpResponse(
                    200,
                    """{"truncated":true,"tree":[]}""",
                    requestValue = request,
                )
            },
            )
        )

        val exception = assertFailsWith<GithubRequestException> {
            client.listBlobPaths("navikt", "demo", "abcdef123456")
        }

        assertEquals(GithubRequestErrorKind.PERMANENT, exception.kind)
        assertContains(exception.message ?: "", "truncated")
    }

    private fun mimeBase64(content: String): String {
        return Base64.getMimeEncoder(60, "\n".toByteArray())
            .encodeToString(content.toByteArray())
            .replace("\n", "\\n")
    }
}

private class MutableClock(
    private var currentInstant: Instant,
    private val zoneId: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zoneId

    override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)

    override fun instant(): Instant = currentInstant

    fun advance(duration: Duration) {
        currentInstant = currentInstant.plus(duration)
    }
}

private data class TestHttpResponse(
    private val statusCodeValue: Int,
    private val bodyValue: String,
    private val headersMap: Map<String, List<String>> = emptyMap(),
    private val requestValue: HttpRequest = HttpRequest.newBuilder().uri(URI("https://example.test")).build(),
) : HttpResponse<String> {
    override fun statusCode(): Int = statusCodeValue
    override fun request(): HttpRequest = requestValue
    override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
    override fun headers(): HttpHeaders = HttpHeaders.of(headersMap) { _, _ -> true }
    override fun body(): String = bodyValue
    override fun sslSession(): Optional<SSLSession> = Optional.empty()
    override fun uri(): URI = requestValue.uri()
    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
}
