package no.nav.github

import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import javax.net.ssl.SSLSession
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GithubCodeScanningClientTest {
    private val tokenProvider = StaticGithubTokenProvider("token")

    @Test
    fun `returns empty list on 404`() {
        val client = clientWithResponse(404, "")

        val result = client.getLatestAnalyses("navikt", "some-repo")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `parses single analysis from valid JSON`() {
        val client = clientWithResponse(
            200,
            """
            [
              {
                "tool": { "name": "CodeQL", "version": "2.19.0" },
                "created_at": "2026-06-28T04:00:00Z",
                "results_count": 5,
                "error": ""
              }
            ]
            """.trimIndent(),
        )

        val result = client.getLatestAnalyses("navikt", "some-repo")

        assertEquals(1, result.size)
        assertEquals("CodeQL", result[0].tool.name)
        assertEquals("2026-06-28T04:00:00Z", result[0].createdAt)
        assertEquals(5, result[0].resultsCount)
        assertEquals("", result[0].error)
    }

    @Test
    fun `parses multiple analyses from valid JSON`() {
        val client = clientWithResponse(
            200,
            """
            [
              {
                "tool": { "name": "CodeQL", "version": "2.19.0" },
                "created_at": "2026-06-28T04:00:00Z",
                "results_count": 5,
                "error": ""
              },
              {
                "tool": { "name": "Trivy", "version": "0.50.1" },
                "created_at": "2026-06-27T03:00:00Z",
                "results_count": 0,
                "error": "exit status 1"
              }
            ]
            """.trimIndent(),
        )

        val result = client.getLatestAnalyses("navikt", "some-repo")

        assertEquals(2, result.size)
        assertEquals("Trivy", result[1].tool.name)
        assertEquals("exit status 1", result[1].error)
    }

    @Test
    fun `ignores unknown JSON fields`() {
        val client = clientWithResponse(
            200,
            """
            [
              {
                "tool": { "name": "CodeQL", "version": "2.19.0", "guid": null },
                "created_at": "2026-06-28T04:00:00Z",
                "results_count": 3,
                "error": "",
                "ref": "refs/heads/main",
                "rules_count": 200,
                "deletable": true
              }
            ]
            """.trimIndent(),
        )

        val result = client.getLatestAnalyses("navikt", "some-repo")

        assertEquals(1, result.size)
        assertEquals("CodeQL", result[0].tool.name)
    }

    @Test
    fun `propagates permanent error on 403`() {
        val client = clientWithResponse(403, """{"message":"Must have push access to repository"}""")

        assertFailsWith<GithubRequestException> {
            client.getLatestAnalyses("navikt", "some-repo")
        }
    }

    @Test
    fun `uses correct endpoint URL`() {
        val requests = mutableListOf<HttpRequest>()
        val client = GithubCodeScanningClient(
            GithubApiClient(
                tokenProvider = tokenProvider,
                userAgent = "test-agent",
                httpSender = HttpStringSender { request ->
                    requests += request
                    TestScanningHttpResponse(200, "[]", request)
                },
            ),
        )

        client.getLatestAnalyses("navikt", "my-repo")

        assertEquals(
            "https://api.github.com/repos/navikt/my-repo/code-scanning/analyses?per_page=100",
            requests.single().uri().toString(),
        )
    }

    private fun clientWithResponse(statusCode: Int, body: String): GithubCodeScanningClient {
        return GithubCodeScanningClient(
            GithubApiClient(
                tokenProvider = tokenProvider,
                httpSender = HttpStringSender { request -> TestScanningHttpResponse(statusCode, body, request) },
            ),
        )
    }
}

private data class TestScanningHttpResponse(
    private val statusCodeValue: Int,
    private val bodyValue: String,
    private val requestValue: HttpRequest,
    private val headersMap: Map<String, List<String>> = emptyMap(),
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
