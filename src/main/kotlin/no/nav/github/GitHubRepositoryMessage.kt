package no.nav.github

import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class GitHubRepositoryMessage(
    val nameWithOwner: String,
    val naisTeams: List<String>,
    val vulnerabilities: List<GitHubVulnerability>
)

@Serializable
data class GitHubVulnerability(
    val severity: String,
    val identifiers: List<VulnerabilityIdentifier>,
    val dependencyScope: String?,
    val dependabotUpdatePullRequestUrl: String?,
    val publishedAt: Instant?,
    val cvssScore: Double?,
    val summary: String?,
    val packageEcosystem: String,
    val packageName: String
)
