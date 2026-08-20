package no.nav.github

import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class FileContentsResponse(
    @SerialName("content")
    val content: String
) {
    fun decode() = Base64.decode(content.replace("\n", "")).decodeToString()
}

@Serializable
internal data class TokenExchangeResponse(
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
data class GithubCodeScanningAnalysis(
    @SerialName("category") val category: String,
    @SerialName("tool") val tool: CodeScanningTool,
    @SerialName("error") val error: String,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("url") val url: String,
)

@Serializable
data class CodeScanningTool(
    @SerialName("name") val name: String,
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

internal data class AccessToken(val value: String, val expiry: Instant)

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
internal data class ReposForTeamResponse(val name: String, val archived: Boolean = false)

// GraphQL response types for vulnerability alerts

@Serializable
internal data class VulnerabilityAlertsGraphQLResponse(
    @SerialName("data") val data: VulnerabilityAlertsData
)

@Serializable
internal data class VulnerabilityAlertsData(
    @SerialName("repository") val repository: VulnerabilityAlertsRepository?
)

@Serializable
internal data class VulnerabilityAlertsRepository(
    @SerialName("vulnerabilityAlerts") val vulnerabilityAlerts: VulnerabilityAlertsConnection
)

@Serializable
internal data class VulnerabilityAlertsConnection(
    @SerialName("nodes") val nodes: List<VulnerabilityAlertNode>,
    @SerialName("pageInfo") val pageInfo: PageInfo
)

@Serializable
internal data class PageInfo(
    @SerialName("hasNextPage") val hasNextPage: Boolean,
    @SerialName("endCursor") val endCursor: String?
)

@Serializable
data class VulnerabilityAlertNode(
    @SerialName("dependencyScope") val dependencyScope: String?,
    @SerialName("dependabotUpdate") val dependabotUpdate: DependabotUpdateInfo?,
    @SerialName("securityAdvisory") val securityAdvisory: GraphQLSecurityAdvisory?,
    @SerialName("securityVulnerability") val securityVulnerability: GraphQLSecurityVulnerability?
)

@Serializable
data class DependabotUpdateInfo(
    @SerialName("pullRequest") val pullRequest: PullRequestInfo?
)

@Serializable
data class PullRequestInfo(
    @SerialName("permalink") val permalink: String
)

@Serializable
data class GraphQLSecurityAdvisory(
    @SerialName("publishedAt") val publishedAt: Instant?,
    @SerialName("cvss") val cvss: CvssInfo?,
    @SerialName("summary") val summary: String?,
    @SerialName("identifiers") val identifiers: List<VulnerabilityIdentifier>
)

@Serializable
data class CvssInfo(
    @SerialName("score") val score: Double
)

@Serializable
data class VulnerabilityIdentifier(
    @SerialName("value") val value: String,
    @SerialName("type") val type: String
)

@Serializable
data class GraphQLSecurityVulnerability(
    @SerialName("severity") val severity: String,
    @SerialName("package") val pkg: GraphQLPackage
)

@Serializable
data class GraphQLPackage(
    @SerialName("ecosystem") val ecosystem: String,
    @SerialName("name") val name: String
)