package no.nav.github

import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.nav.Whodis
import no.nav.kafka.KafkaSenderInterface

@Serializable
data class GitHubCollectRequest(
    val teams: List<String> = emptyList(),
    val repositories: List<String> = emptyList()
)

class GitHubCollectHandler(
    private val gitHub: GitHub,
    private val whodis: Whodis,
    private val kafka: KafkaSenderInterface
) {
    private val logger = KtorSimpleLogger(this::class.java.name)

    suspend fun collect(request: GitHubCollectRequest) {
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

        // 3. For each unique repo, fetch vulnerability alerts and publish
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
                    dependabotUpdatePullRequestUrl = alert.dependabotUpdate?.pullRequest?.permalink,
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

            kafka.sendToKafka("github_vulnerability_data", Json.encodeToString(message))
            logger.info("Published vulnerability data for $nameWithOwner (${vulnerabilities.size} alerts)")
        }
    }
}
