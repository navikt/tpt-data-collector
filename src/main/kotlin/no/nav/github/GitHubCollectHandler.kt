package no.nav.github

import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import no.nav.tpt.TptBackend
import no.nav.whodis.Whodis
import java.time.Instant

@Serializable
data class GitHubCollectRequest(
    val teams: List<String> = emptyList(),
    val repositories: List<String> = emptyList()
)

@Serializable
data class GitHubSyncEvent(
    val teams: List<String>,
    val timestamp: String
)

class GitHubCollectHandler(
    private val gitHub: GitHub,
    private val whodis: Whodis,
    private val backend: TptBackend
) {
    private val logger = KtorSimpleLogger(this::class.java.name)

    suspend fun collect(request: GitHubCollectRequest) {
        try {
            backend.sendSyncStarted(GitHubSyncEvent(teams = request.teams, timestamp = Instant.now().toString()))
            logger.info("Sent GitHub sync started for teams ${request.teams}")
            collectVulnerabilityData(request)
        } finally {
            // The frontend waits for this signal, so it must be sent exactly once, even if collection fails or is cancelled.
            withContext(NonCancellable) {
                backend.sendSyncComplete(GitHubSyncEvent(teams = request.teams, timestamp = Instant.now().toString()))
                logger.info("Sent GitHub sync complete for teams ${request.teams}")
            }
        }
    }

    private suspend fun collectVulnerabilityData(request: GitHubCollectRequest) {
        // 1. Resolve repos for each team via whodis
        val repoToTeams = mutableMapOf<String, MutableSet<String>>()

        for (teamSlug in request.teams) {
            val repos = try {
                whodis.repositoriesForTeam(teamSlug)
            } catch (e: Exception) {
                logger.error("Failed to fetch repositories for team $teamSlug from whodis", e)
                emptyList()
            }
            for (repo in repos) {
                repoToTeams.getOrPut(repo) { mutableSetOf() }.add(teamSlug)
            }
        }

        // 2. Merge with directly specified repos (no owning team from teams list)
        for (repo in request.repositories) {
            repoToTeams.getOrPut(repo) { mutableSetOf() }
        }

        // 3. For each unique repo, fetch vulnerability alerts and send them to the backend
        for ((nameWithOwner, owningTeams) in repoToTeams) {
            val parts = nameWithOwner.split("/")
            if (parts.size != 2) {
                logger.warn("Skipping repo with unexpected format: $nameWithOwner")
                continue
            }
            val (owner, repo) = parts

            val alerts = try {
                gitHub.vulnerabilityAlertsFor(owner, repo)
            } catch (e: Exception) {
                logger.error("Failed to fetch vulnerability alerts for $nameWithOwner", e)
                continue
            }

            val vulnerabilities = alerts.mapNotNull { alert ->
                val vuln = alert.securityVulnerability ?: return@mapNotNull null
                GitHubVulnerability(
                    severity = vuln.severity,
                    identifiers = alert.securityAdvisory?.identifiers ?: emptyList(),
                    dependencyScope = alert.dependencyScope,
                    // GitHub keeps the last Dependabot PR on an alert even after it is closed/merged
                    dependabotUpdatePullRequestUrl = alert.dependabotUpdate?.pullRequest
                        ?.takeIf { it.state == "OPEN" }
                        ?.permalink,
                    publishedAt = alert.securityAdvisory?.publishedAt,
                    cvssScore = alert.securityAdvisory?.cvss?.score,
                    summary = alert.securityAdvisory?.summary,
                    packageEcosystem = vuln.pkg.ecosystem,
                    packageName = vuln.pkg.name
                )
            }

            val message = GitHubRepositoryMessage(
                nameWithOwner = nameWithOwner,
                naisTeams = owningTeams.sorted(),
                vulnerabilities = vulnerabilities
            )

            if (backend.sendVulnerabilityData(message)) {
                logger.info("Sent vulnerability data for $nameWithOwner (${vulnerabilities.size} alerts)")
            }
        }
    }
}
