package no.nav.github

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.call.body
import io.ktor.http.HttpHeaders.Link
import io.ktor.http.HttpMethod
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.http.HttpStatusCode
import io.ktor.util.logging.KtorSimpleLogger
import java.util.Date
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface GitHub {
    suspend fun readFileContents(nameWithOwner: String, filePath: String): String
    suspend fun hasOpenCriticalDependabotAlerts(nameWithOwner: String): DependabotAlertsResult
    suspend fun allFilePathsIn(nameWithOwner: String): List<String>
    /** Complete non-archived repository list, or failure; collection has a 60-second overall deadline. */
    suspend fun allReposForTeam(teamName: String): List<String>
    /** Newest analysis per category within the 100 most recent analyses; older categories are excluded. */
    suspend fun latestCodeScanningAnalysesFor(nameWithOwner: String): List<GithubCodeScanningAnalysis>
    suspend fun vulnerabilityAlertsFor(nameWithOwner: String): List<VulnerabilityAlertNode>
    suspend fun latestCommitTimeFor(nameWithOwner: String): Instant
}

open class FakeGitHub: GitHub {
    override suspend fun readFileContents(nameWithOwner: String, filePath: String): String {
        return ""
    }

    override suspend fun hasOpenCriticalDependabotAlerts(nameWithOwner: String): DependabotAlertsResult =
        DependabotAlertsResult.Available(true)

    override suspend fun latestCodeScanningAnalysesFor(nameWithOwner: String): List<GithubCodeScanningAnalysis> {
        return listOf(GithubCodeScanningAnalysis(
            ".github/workflows/codeql-analysis.yml:analyse/language:perl",
            CodeScanningTool("CodeQL"),
            "",
            Clock.System.now(),
            "https://api.github.com/repos/octocat/hello-world/code-scanning/analyses/201"
        ))
    }

    override suspend fun allFilePathsIn(nameWithOwner: String): List<String> = emptyList()

    override suspend fun allReposForTeam(teamName: String): List<String> = emptyList()

    override suspend fun vulnerabilityAlertsFor(nameWithOwner: String): List<VulnerabilityAlertNode> = emptyList()
    override suspend fun latestCommitTimeFor(nameWithOwner: String) =
        if (nameWithOwner.substringAfter('/') == "goodOne") Clock.System.now() - 29.days else Clock.System.now() - 31.days
}

@OptIn(ExperimentalAtomicApi::class)
class RealGitHub internal constructor(
    val httpClient: HttpClient,
    val appId: String,
    val installationId: String,
    privateKeyPEM: String,
    private val organization: String,
    private val transport: GitHubTransport
): GitHub {
    constructor(httpClient: HttpClient, appId: String, installationId: String, privateKeyPEM: String, organization: String) :
        this(httpClient, appId, installationId, privateKeyPEM, organization, GitHubTransport(httpClient))
    private val apiBaseUrl = "https://api.github.com"
    val logger = KtorSimpleLogger(this::class.java.name)

    private val jwk = JWK.parseFromPEMEncodedObjects(privateKeyPEM).toRSAKey()
    private val signer = RSASSASigner( JWK.parseFromPEMEncodedObjects(privateKeyPEM).toRSAKey())
    private var installationAccessToken: AtomicReference<AccessToken?> = AtomicReference(null)

    override suspend fun readFileContents(nameWithOwner: String, filePath: String): String {
        requireQualifiedRepositoryName(nameWithOwner)
        val url = "$apiBaseUrl/repos/$nameWithOwner/contents/$filePath"
        val authToken = retrieveAccessToken()
        val response: FileContentsResponse = makeHttpRequest(GitHubOperation.FILE_CONTENTS, nameWithOwner, Get, url, authToken)
        return response.decode()
    }

    override suspend fun hasOpenCriticalDependabotAlerts(nameWithOwner: String): DependabotAlertsResult {
        requireQualifiedRepositoryName(nameWithOwner)
        val url = "$apiBaseUrl/repos/$nameWithOwner/dependabot/alerts?state=open&severity=critical&per_page=1"
        val authToken = retrieveAccessToken()
        return transport.execute(GitHubOperation.DEPENDABOT_ALERTS, nameWithOwner) {
            try {
                val response = request(Get, url, authToken).body<List<DependabotAlert>>()
                DependabotAlertsResult.Available(response.isNotEmpty())
            } catch (e: ClientRequestException) {
                when {
                    e.isDependabotDisabled() -> DependabotAlertsResult.Disabled
                    e.response.status == HttpStatusCode.NotFound -> DependabotAlertsResult.Available(false)
                    else -> throw e
                }
            }
        }
    }

    private suspend fun ClientRequestException.isDependabotDisabled(): Boolean =
        response.status == HttpStatusCode.Forbidden &&
            response.headers["Retry-After"] == null &&
            response.headers["X-RateLimit-Remaining"]?.trim()?.toLongOrNull() != 0L &&
            response.gitHubErrorMessage() == "Dependabot alerts are disabled for this repository."

    override suspend fun latestCodeScanningAnalysesFor(nameWithOwner: String): List<GithubCodeScanningAnalysis> {
        requireQualifiedRepositoryName(nameWithOwner)
        val url = "$apiBaseUrl/repos/$nameWithOwner/code-scanning/analyses?per_page=100&sort=created&direction=desc"
        val authToken = retrieveAccessToken()
        return transport.execute(GitHubOperation.CODE_SCANNING, nameWithOwner) {
            try {
                val response = request(Get, url, authToken).body<List<GithubCodeScanningAnalysis>>()
                response
                    .groupBy { it.category }
                    .map { (_, analyses) -> analyses.maxBy { it.createdAt } }
            } catch (e: ClientRequestException) {
                if (e.response.status == HttpStatusCode.NotFound) emptyList() else throw e
            }
        }
    }

    override suspend fun allFilePathsIn(nameWithOwner: String): List<String> =
        allFilePathsIn(nameWithOwner, TreeInventoryLimits())

    internal suspend fun allFilePathsIn(nameWithOwner: String, limits: TreeInventoryLimits): List<String> {
        requireQualifiedRepositoryName(nameWithOwner)
        return withTimeoutOrNull(limits.timeout) {
            val rootRepoUrl = "$apiBaseUrl/repos/$nameWithOwner"
            val authToken = retrieveAccessToken()
            val rootRepoResponse: RepoRootResponse =
                makeHttpRequest(GitHubOperation.REPOSITORY, nameWithOwner, Get, rootRepoUrl, authToken)
            GitHubTreeInventory(nameWithOwner, limits) { ref, recursive ->
                val treeUrl = "$rootRepoUrl/git/trees/$ref" + if (recursive) "?recursive=true" else ""
                transport.execute(GitHubOperation.TREE, nameWithOwner) {
                    try {
                        request(Get, treeUrl, authToken).body<TreeResponse>()
                    } catch (ex: ClientRequestException) {
                        if (recursive && ex.response.isEmptyGitHubRepository()) throw GitHubEmptyRepositoryException()
                        throw ex
                    }
                }
            }.paths(rootRepoResponse.defaultBranch)
        } ?: throw GitHubFileInventoryException(nameWithOwner, "inventory time budget exceeded")
    }

    override suspend fun allReposForTeam(teamName: String): List<String> =
        withTimeoutOrNull(60.seconds) {
            val repos = mutableListOf<ReposForTeamResponse>()
            val pagination = GitHubPagination()
            var url: String? = "$apiBaseUrl/orgs/$organization/teams/$teamName/repos?per_page=100"
            while (url != null) {
                yield()
                val pageUrl = pagination.visit(url)
                val authToken = retrieveAccessToken()
                val (page, next) = transport.execute(GitHubOperation.TEAM_REPOSITORIES, teamName) {
                    val response = request(Get, pageUrl, authToken)
                    response.body<List<ReposForTeamResponse>>() to
                        nextPageUrl(response.headers.getAll(Link)?.joinToString(","))
                }
                repos += page
                url = next
            }
            repos.filter { !it.archived }.map { it.nameWithOwner }
        } ?: throw GitHubPaginationException("team repository collection time budget exceeded")

    override suspend fun vulnerabilityAlertsFor(nameWithOwner: String): List<VulnerabilityAlertNode> {
        requireQualifiedRepositoryName(nameWithOwner)
        val (owner, repo) = nameWithOwner.split('/')
        val graphqlUrl = "$apiBaseUrl/graphql"
        val authToken = retrieveAccessToken()
        val query = """
            query (${"$"}owner: String!, ${"$"}repo: String!, ${"$"}vulnEndCursor: String) {
              repository(owner: ${"$"}owner, name: ${"$"}repo) {
                vulnerabilityAlerts(first: 100, after: ${"$"}vulnEndCursor, states: OPEN) {
                  nodes {
                    dependencyScope
                    dependabotUpdate { pullRequest { permalink state } }
                    securityAdvisory {
                      publishedAt
                      cvss { score }
                      summary
                      identifiers { value type }
                    }
                    securityVulnerability {
                      severity
                      package { ecosystem name }
                    }
                  }
                  pageInfo { hasNextPage endCursor }
                }
              }
            }
        """.trimIndent()

        val allAlerts = mutableListOf<VulnerabilityAlertNode>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val requestBody = Json.encodeToString(
                buildJsonObject {
                    put("query", query)
                    put("variables", buildJsonObject {
                        put("owner", owner)
                        put("repo", repo)
                        put("vulnEndCursor", cursor?.let { JsonPrimitive(it) } ?: JsonNull)
                    })
                }
            )
            val response = transport.execute(GitHubOperation.VULNERABILITY_ALERTS, nameWithOwner) {
                val httpResponse = request(Post, graphqlUrl, authToken, requestBody)
                httpResponse.body<VulnerabilityAlertsGraphQLResponse>().also {
                    checkGraphQLRateLimit(httpResponse, it.errors)
                }
            }
            fun incomplete(reason: String): Nothing =
                throw GitHubVulnerabilityCollectionException(nameWithOwner, reason)

            if (response.errors.isNotEmpty()) {
                // Upstream messages can contain request data; report the count, not the payload.
                incomplete("GraphQL returned ${response.errors.size} error(s)")
            }
            val data = response.data ?: incomplete("GraphQL data is null or missing")
            val repository = data.repository ?: incomplete("GraphQL repository is null")
            val alerts = repository.vulnerabilityAlerts ?: incomplete("GraphQL vulnerabilityAlerts connection is null")
            cursor = if (alerts.pageInfo.hasNextPage) {
                val nextCursor = alerts.pageInfo.endCursor
                if (nextCursor.isNullOrBlank()) {
                    incomplete("GraphQL hasNextPage requires a non-blank endCursor")
                }
                if (!seenCursors.add(nextCursor)) {
                    incomplete("GraphQL pagination cursor did not advance")
                }
                nextCursor
            } else null
            allAlerts.addAll(alerts.nodes)
        } while (cursor != null)

        return allAlerts
    }

    override suspend fun latestCommitTimeFor(nameWithOwner: String): Instant {
        requireQualifiedRepositoryName(nameWithOwner)
        val url = "$apiBaseUrl/repos/$nameWithOwner/commits"
        val authToken = retrieveAccessToken()
        val commitsResponse: List<CommitResponse> =
            makeHttpRequest(GitHubOperation.COMMITS, nameWithOwner, Get, url, authToken)
        return commitsResponse.maxOf { it.commit.author.date }
    }

    private suspend inline fun <reified T> makeHttpRequest(
        operation: GitHubOperation,
        context: String?,
        httpMethod: HttpMethod,
        url: String,
        authToken: String
    ): T =
        transport.execute(operation, context) {
            request(httpMethod, url, authToken).body()
        }

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
        val response = makeHttpRequest<TokenExchangeResponse>(
            GitHubOperation.INSTALLATION_TOKEN, null, Post, uri, this.serialize()
        )
        return AccessToken(response.token, response.expiresAt)
    }
}

internal class GitHubVulnerabilityCollectionException(nameWithOwner: String, reason: String) :
    IllegalStateException("Incomplete GitHub vulnerability collection for $nameWithOwner: $reason")

internal fun needsRefresh(now: Instant = Clock.System.now(), expiresAt: Instant): Boolean {
    val in10Mins = now.plus(10.minutes)
    val diff = in10Mins.toEpochMilliseconds() - expiresAt.toEpochMilliseconds()
    return diff > 0
}
