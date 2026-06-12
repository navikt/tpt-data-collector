package no.nav.github

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.io.StringReader
import java.security.interfaces.RSAPrivateKey
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*

interface GitHubTokenProvider {
    suspend fun getInstallationToken(): String
}

class GitHubAppAuth(
    private val appId: String,
    private val privateKeyContent: String,
    private val installationId: String,
    private val httpClient: HttpClient,
) : GitHubTokenProvider {
    private val privateKey: RSAPrivateKey = loadPrivateKey(privateKeyContent)
    private val mutex = Mutex()

    @Volatile
    private var cache: CachedToken? = null

    private data class CachedToken(
        val token: String,
        val expiresAt: Instant,
    )

    private fun CachedToken.isValid() = Instant.now().isBefore(expiresAt.minusSeconds(300))

    override suspend fun getInstallationToken(): String {
        cache?.takeIf { it.isValid() }?.let { return it.token }
        return mutex.withLock {
            cache?.takeIf { it.isValid() }?.let { return@withLock it.token }

            val jwt =
                JWT
                    .create()
                    .withIssuer(appId)
                    .withIssuedAt(Date.from(Instant.now().minusSeconds(60)))
                    .withExpiresAt(Date.from(Instant.now().plusSeconds(600)))
                    .sign(Algorithm.RSA256(null, privateKey))

            val response =
                httpClient
                    .post("https://api.github.com/app/installations/$installationId/access_tokens") {
                        headers {
                            append(HttpHeaders.Accept, "application/vnd.github+json")
                            append(HttpHeaders.Authorization, "Bearer $jwt")
                            append("X-GitHub-Api-Version", "2026-03-10")
                        }
                    }
            if (!response.status.isSuccess()) {
                logger.warn("Failed to fetch GitHub App installation token: HTTP ${response.status.value}")
                throw IllegalStateException("Failed to fetch GitHub App installation token: HTTP ${response.status.value}")
            }
            val tokenResponse = response.body<InstallationTokenResponse>()

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

    private fun loadPrivateKey(pemContent: String): RSAPrivateKey {
        val pemParser = PEMParser(StringReader(pemContent))
        val keyPair = pemParser.readObject() as PEMKeyPair
        return JcaPEMKeyConverter().getKeyPair(keyPair).private as RSAPrivateKey
    }

    @Serializable
    private data class InstallationTokenResponse(
        val token: String,
        val expires_at: String,
    )
}

