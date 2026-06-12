package no.nav.github

import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.junit.jupiter.api.Test
import java.io.StringWriter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
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
import kotlin.test.assertTrue

class GithubAppAuthTest {
    @Test
    fun `getToken fetches installation token and reuses cached value before refresh window`() {
        val clock = AppAuthMutableClock(Instant.parse("2026-06-12T10:00:00Z"))
        val requests = mutableListOf<HttpRequest>()
        var calls = 0
        val auth = GithubAppAuth(
            appId = "12345",
            privateKeyContent = generatePrivateKeyPem(),
            installationId = "67890",
            userAgent = "test-agent",
            clock = clock,
            httpSender = HttpStringSender { request ->
                calls += 1
                requests += request
                AppAuthTestHttpResponse(
                    statusCodeValue = 201,
                    bodyValue = """
                        {
                          "token": "installation-token-1",
                          "expires_at": "2026-06-12T11:00:00Z"
                        }
                    """.trimIndent(),
                    requestValue = request,
                )
            },
        )

        val first = auth.getToken()
        val second = auth.getToken()

        assertEquals("installation-token-1", first)
        assertEquals("installation-token-1", second)
        assertEquals(1, calls)
        assertEquals("https://api.github.com/app/installations/67890/access_tokens", requests.single().uri().toString())
        assertEquals("test-agent", requests.single().headers().firstValue("User-Agent").orElseThrow())
        assertEquals("application/vnd.github+json", requests.single().headers().firstValue("Accept").orElseThrow())
        assertEquals("2022-11-28", requests.single().headers().firstValue("X-GitHub-Api-Version").orElseThrow())
        val authHeader = requests.single().headers().firstValue("Authorization").orElseThrow()
        assertTrue(authHeader.startsWith("Bearer "))
        val jwtPayload = decodeJwtPayload(authHeader.removePrefix("Bearer "))
        assertContains(jwtPayload, "\"iss\":\"12345\"")
    }

    @Test
    fun `getToken refreshes cached token inside refresh window`() {
        val clock = AppAuthMutableClock(Instant.parse("2026-06-12T10:00:00Z"))
        var calls = 0
        val auth = GithubAppAuth(
            appId = "12345",
            privateKeyContent = generatePrivateKeyPem(),
            installationId = "67890",
            clock = clock,
            httpSender = HttpStringSender { request ->
                calls += 1
                val body = when (calls) {
                    1 -> """{"token":"installation-token-1","expires_at":"2026-06-12T10:06:00Z"}"""
                    else -> """{"token":"installation-token-2","expires_at":"2026-06-12T11:06:00Z"}"""
                }
                AppAuthTestHttpResponse(statusCodeValue = 201, bodyValue = body, requestValue = request)
            },
        )

        val first = auth.getToken()
        clock.advance(Duration.ofMinutes(2))
        val second = auth.getToken()

        assertEquals("installation-token-1", first)
        assertEquals("installation-token-2", second)
        assertEquals(2, calls)
    }

    @Test
    fun `getToken fails when GitHub rejects installation token request`() {
        val auth = GithubAppAuth(
            appId = "12345",
            privateKeyContent = generatePrivateKeyPem(),
            installationId = "67890",
            httpSender = HttpStringSender { request ->
                AppAuthTestHttpResponse(
                    statusCodeValue = 403,
                    bodyValue = """{"message":"forbidden"}""",
                    requestValue = request,
                )
            },
        )

        val exception = assertFailsWith<IllegalStateException> {
            auth.getToken()
        }

        assertContains(exception.message ?: "", "HTTP 403")
    }

    @Test
    fun `getToken fails when GitHub returns invalid JSON`() {
        val auth = GithubAppAuth(
            appId = "12345",
            privateKeyContent = generatePrivateKeyPem(),
            installationId = "67890",
            httpSender = HttpStringSender { request ->
                AppAuthTestHttpResponse(
                    statusCodeValue = 201,
                    bodyValue = """{"token":123}""",
                    requestValue = request,
                )
            },
        )

        val exception = assertFailsWith<IllegalStateException> {
            auth.getToken()
        }

        assertContains(exception.message ?: "", "decode")
    }

    private fun generatePrivateKeyPem(): String {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        return StringWriter().use { stringWriter ->
            JcaPEMWriter(stringWriter).use { pemWriter ->
                pemWriter.writeObject(keyPair.private)
            }
            stringWriter.toString()
        }
    }

    private fun decodeJwtPayload(jwt: String): String {
        val payload = jwt.split(".")[1]
        return String(Base64.getUrlDecoder().decode(payload))
    }
}

private class AppAuthMutableClock(
    private var currentInstant: Instant,
    private val zoneId: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zoneId

    override fun withZone(zone: ZoneId): Clock = AppAuthMutableClock(currentInstant, zone)

    override fun instant(): Instant = currentInstant

    fun advance(duration: Duration) {
        currentInstant = currentInstant.plus(duration)
    }
}

private data class AppAuthTestHttpResponse(
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
