package no.nav.github

import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.io.StringReader
import java.io.IOException
import java.net.URI
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface GithubTokenProvider {
    fun getToken(): String
}

class StaticGithubTokenProvider(
    private val token: String,
) : GithubTokenProvider {
    init {
        require(token.isNotBlank()) { "GitHub token must not be blank" }
    }

    override fun getToken(): String = token
}

class GithubAppAuth internal constructor(
    private val appId: String,
    private val privateKeyContent: String,
    private val installationId: String,
    private val userAgent: String = GithubApiClient.DEFAULT_USER_AGENT,
    private val apiBaseUrl: String = GithubApiClient.DEFAULT_API_BASE_URL,
    private val clock: Clock = Clock.systemUTC(),
    private val httpSender: HttpStringSender = JavaHttpStringSender(),
) : GithubTokenProvider {
    private val logger = KtorSimpleLogger(this::class.java.name)
    private val json = Json { ignoreUnknownKeys = true }
    private val privateKey: RSAPrivateKey = loadPrivateKey(privateKeyContent)
    private val lock = ReentrantLock()

    @Volatile
    private var cache: CachedToken? = null

    private data class CachedToken(
        val token: String,
        val expiresAt: Instant,
    )

    init {
        require(appId.isNotBlank()) { "GitHub App ID must not be blank" }
        require(installationId.isNotBlank()) { "GitHub App installation ID must not be blank" }
        require(privateKeyContent.isNotBlank()) { "GitHub App private key must not be blank" }
    }

    override fun getToken(): String {
        cache?.takeIf { it.isValid(clock.instant()) }?.let { return it.token }
        return lock.withLock {
            cache?.takeIf { it.isValid(clock.instant()) }?.let { return@withLock it.token }

            val jwt = createAppJwt(clock.instant())

            val request = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl.trimEnd('/') + "/app/installations/$installationId/access_tokens"))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer $jwt")
                .header("User-Agent", userAgent)
                .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
            val response = try {
                httpSender.send(request)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: IOException) {
                throw IllegalStateException("Failed to fetch GitHub App installation token: ${e.message}", e)
            }

            if (response.statusCode() !in 200..299) {
                logger.warn("Failed to fetch GitHub App installation token: HTTP ${response.statusCode()}")
                throw IllegalStateException(
                    "Failed to fetch GitHub App installation token: HTTP ${response.statusCode()}: ${response.body().trim().take(300)}",
                )
            }
            val tokenResponse = try {
                json.decodeFromString<InstallationTokenResponse>(response.body())
            } catch (e: SerializationException) {
                throw IllegalStateException("Failed to decode GitHub App installation token response", e)
            }

            cache =
                CachedToken(
                    token = tokenResponse.token,
                    expiresAt = Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(tokenResponse.expires_at)),
                )
            logger.info(
                "Fetched installation token for GitHub App with ID $appId and installation ID $installationId, expires at: (${tokenResponse.expires_at})",
            )

            tokenResponse.token
        }
    }

    private fun CachedToken.isValid(now: Instant): Boolean {
        return now.isBefore(expiresAt.minusSeconds(TOKEN_REFRESH_SKEW_SECONDS))
    }

    private fun createAppJwt(now: Instant): String {
        val headerJson = """{"alg":"RS256","typ":"JWT"}"""
        val payloadJson =
            """{"iat":${now.minusSeconds(JWT_ISSUED_AT_SKEW_SECONDS).epochSecond},"exp":${now.plusSeconds(JWT_TTL_SECONDS).epochSecond},"iss":"$appId"}"""
        val header = Base64.getUrlEncoder().withoutPadding().encodeToString(headerJson.toByteArray(StandardCharsets.UTF_8))
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.toByteArray(StandardCharsets.UTF_8))
        val signingInput = "$header.$payload"
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(signingInput.toByteArray(StandardCharsets.UTF_8))
            sign()
        }
        val encodedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        return "$signingInput.$encodedSignature"
    }

    private fun loadPrivateKey(pemContent: String): RSAPrivateKey {
        PEMParser(StringReader(pemContent)).use { pemParser ->
            val pemObject = pemParser.readObject() ?: throw IllegalArgumentException("GitHub App private key is empty")
            val converter = JcaPEMKeyConverter()
            val privateKey = when (pemObject) {
                is PEMKeyPair -> converter.getKeyPair(pemObject).private
                is PrivateKeyInfo -> converter.getPrivateKey(pemObject)
                else -> throw IllegalArgumentException("Unsupported GitHub App private key format")
            }
            return privateKey as? RSAPrivateKey
                ?: throw IllegalArgumentException("GitHub App private key must be an RSA private key")
        }
    }

    @Serializable
    private data class InstallationTokenResponse(
        val token: String,
        val expires_at: String,
    )

    private companion object {
        private const val GITHUB_API_VERSION = "2022-11-28"
        private const val JWT_ISSUED_AT_SKEW_SECONDS = 60L
        private const val JWT_TTL_SECONDS = 600L
        private const val TOKEN_REFRESH_SKEW_SECONDS = 300L
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
