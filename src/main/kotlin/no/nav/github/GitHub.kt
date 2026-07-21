package no.nav.github

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.http.HttpHeaders.Accept
import io.ktor.http.HttpHeaders.Authorization
import io.ktor.http.HttpHeaders.UserAgent
import io.ktor.http.HttpMethod
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.util.logging.KtorSimpleLogger
import java.util.Date
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface GitHub {
    suspend fun readFileContents(repoName: String, filePath: String): String
    suspend fun dependabotSecurityAlertsFor(repoName: String): Map<String, String>
    suspend fun allFilePathsIn(repoName: String): List<String>
    suspend fun allReposForTeam(teamName: String): List<String>
    suspend fun ping(): Boolean
}

class FakeGitHub: GitHub {
    override suspend fun readFileContents(repoName: String, filePath: String): String {
        return ""
    }

    override suspend fun dependabotSecurityAlertsFor(repoName: String): Map<String, String> {
        return mapOf("yololib" to "medium", "boguslib" to "critical")
    }

    override suspend fun allFilePathsIn(repoName: String): List<String> = emptyList()

    override suspend fun allReposForTeam(teamName: String): List<String> = emptyList()

    override suspend fun ping() = true
}

@OptIn(ExperimentalAtomicApi::class)
class RealGitHub(val httpClient: HttpClient, val appId: String, val installationId: String, privateKeyPEM: String): GitHub {
    private val apiBaseUrl = "https://api.github.com"
    val logger = KtorSimpleLogger(this::class.java.name)

    private val jwk = JWK.parseFromPEMEncodedObjects(privateKeyPEM).toRSAKey()
    private val signer = RSASSASigner( JWK.parseFromPEMEncodedObjects(privateKeyPEM).toRSAKey())
    private var installationAccessToken: AtomicReference<AccessToken?> = AtomicReference(null)

    override suspend fun readFileContents(repoName: String, filePath: String): String {
        val url = "$apiBaseUrl/repos/navikt/$repoName/contents/$filePath"
        val authToken = retrieveAccessToken()
        val response: FileContentsResponse = makeHttpRequest(Get, url, authToken)
        return response.decode()
    }

    override suspend fun dependabotSecurityAlertsFor(repoName: String): Map<String, String> {
        val url = "$apiBaseUrl/repos/navikt/$repoName/dependabot/alerts"
        val authToken = retrieveAccessToken()
        val response: List<DependabotAlert> = makeHttpRequest(Get, url, authToken)
        return response.flatMap {
            it.advisory.vulnerabilities
        }.associate {
            it.pkg.name to it.severity
        }
    }

    override suspend fun allFilePathsIn(repoName: String): List<String> {
        val rootRepoUrl = "$apiBaseUrl/repos/navikt/$repoName"
        val authToken = retrieveAccessToken()
        val rootRepoResponse: RepoRootResponse = makeHttpRequest(Get, rootRepoUrl, authToken)
        val treeUrl = "$apiBaseUrl/repos/navikt/$repoName/git/trees/${rootRepoResponse.defaultBranch}?recursive=true"
        val treeResponse: TreeResponse = makeHttpRequest(Get, treeUrl, authToken)
        return treeResponse.tree.map { it.path }
    }

    override suspend fun allReposForTeam(teamName: String): List<String> {
        val url = "$apiBaseUrl/orgs/navikt/teams/$teamName/repos"
        val authToken = retrieveAccessToken()
        val reposResponse: List<ReposForTeamResponse> = makeHttpRequest(Get, url, authToken)
        return reposResponse.map { it.name }
    }

    override suspend fun ping(): Boolean {
        val authToken = retrieveAccessToken()
        val response: String = makeHttpRequest(Get, apiBaseUrl, authToken)
        return response.isNotEmpty()
    }

    private suspend inline fun <reified T> makeHttpRequest(httpMethod: HttpMethod, url: String, authToken: String): T =
        httpClient.request(url) {
            method = httpMethod
            header(Authorization, "Bearer $authToken")
            header(Accept, "application/json")
            header(UserAgent, "Nav IT McBotface")
        }.body()

    private suspend fun retrieveAccessToken(): String {
        if (!tokenNeedsRefresh()) {
            return installationAccessToken.load()?.value ?: ""
        }
        logger.info("Refreshing access token")
        val newAccessToken = createExchangeToken().andExchangeForInstallationToken()
        installationAccessToken.store(newAccessToken)
        return installationAccessToken.load()?.value ?: ""
    }

    private fun createExchangeToken(): SignedJWT {
        val now = Clock.System.now()
        val in10Mins = now.plus(10.minutes)
        val claims = JWTClaimsSet.Builder()
            .issueTime(Date(now.toEpochMilliseconds()))
            .notBeforeTime(Date(now.toEpochMilliseconds()))
            .issuer(appId)
            .expirationTime(Date(in10Mins.toEpochMilliseconds()))
            .build()

        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(jwk.keyID).build(), claims).also {
            it.sign(signer)
        }
    }

    private fun tokenNeedsRefresh() = installationAccessToken.load()?.let { cachedToken ->
        needsRefresh(expiresAt = cachedToken.expiry)
    } ?: true

    private suspend fun SignedJWT.andExchangeForInstallationToken(): AccessToken {
        val uri = "$apiBaseUrl/app/installations/$installationId/access_tokens"
        val response = makeHttpRequest<TokenExchangeResponse>(HttpMethod.Post, uri, this.serialize())
        return AccessToken(response.token, response.expiresAt)
    }
}

internal fun needsRefresh(now: Instant = Clock.System.now(), expiresAt: Instant): Boolean {
    val in10Mins = now.plus(10.minutes)
    val diff = in10Mins.toEpochMilliseconds() - expiresAt.toEpochMilliseconds()
    return diff > 0
}

@Serializable
private data class FileContentsResponse(
    @SerialName("content")
    val content: String
) {
    fun decode() = Base64.decode(content.replace("\n", "")).decodeToString()
}

@Serializable
private data class TokenExchangeResponse(
    @SerialName("token")
    val token: String,
    @SerialName("expires_at")
    val expiresAt: Instant
)

@Serializable
internal data class DependabotAlert(
    @SerialName("security_advisory")
    val advisory: SecurityAdvisory
)

@Serializable
internal data class SecurityAdvisory(
    @SerialName("vulnerabilities")
    val vulnerabilities: List<Vulnerability>
)

@Serializable
internal data class Vulnerability(
    @SerialName("package")
    val pkg: Package,
    @SerialName("severity")
    val severity: String
)

@Serializable
internal data class Package(
    @SerialName("ecosystem")
    val ecosystem: String,
    @SerialName("name")
    val name: String
)

private data class AccessToken(val value: String, val expiry: Instant)

@Serializable
internal data class RepoRootResponse(
    @SerialName("default_branch")
    val defaultBranch: String
)

@Serializable
internal data class TreeResponse(
    @SerialName("tree")
    val tree: List<TreeEntry>
)

@Serializable
internal data class TreeEntry(
    @SerialName("path")
    val path: String,
)

@Serializable
data class ReposForTeamResponse(val name: String)