package no.nav.github

import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import no.nav.tpt.TptBackend
import no.nav.whodis.Whodis
import java.time.Instant
import kotlin.time.TimeSource
import no.nav.logging.logEvent
import no.nav.logging.logFailure
import org.slf4j.event.Level

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
        val started = TimeSource.Monotonic.markNow()
        val summary = CollectionSummary()
        var syncStartedSent = false
        var syncCompleteSent = false
        var outcome = "failed"
        logger.logEvent(
            Level.INFO, "GitHub collection started",
            "team_count" to request.teams.size, "repository_count" to request.repositories.size
        )
        try {
            syncStartedSent = backend.sendSyncStarted(
                GitHubSyncEvent(teams = request.teams, timestamp = Instant.now().toString())
            )
            collectVulnerabilityData(request, summary)
            outcome = if (summary.repositoriesFailed > 0 || summary.teamsFailed > 0 || !syncStartedSent) {
                "partial"
            } else "success"
        } catch (e: CancellationException) {
            outcome = "cancelled"
            throw e
        } finally {
            // The frontend waits for this signal, so it must be sent exactly once, even if collection fails or is cancelled.
            withContext(NonCancellable) {
                try {
                    syncCompleteSent = backend.sendSyncComplete(
                        GitHubSyncEvent(teams = request.teams, timestamp = Instant.now().toString())
                    )
                } finally {
                    logger.logEvent(
                        Level.INFO, "GitHub collection finished",
                        "outcome" to if (outcome == "success" && !syncCompleteSent) "partial" else outcome,
                        "repositories_total" to summary.repositoriesTotal,
                        "repositories_sent" to summary.repositoriesSent,
                        "repositories_failed" to summary.repositoriesFailed,
                        "teams_failed" to summary.teamsFailed,
                        "alerts_sent" to summary.alertsSent,
                        "sync_started_sent" to syncStartedSent, "sync_complete_sent" to syncCompleteSent,
                        "duration_ms" to started.elapsedNow().inWholeMilliseconds
                    )
                }
            }
        }
    }

    private suspend fun collectVulnerabilityData(request: GitHubCollectRequest, summary: CollectionSummary) {
        // 1. Resolve repos for each team via whodis
        val repoToTeams = mutableMapOf<String, MutableSet<String>>()

        for (teamSlug in request.teams) {
            val repos = try {
                whodis.repositoriesForTeam(teamSlug)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                summary.teamsFailed++
                logger.logFailure(
                    "Team repository lookup failed; team repositories not collected", e,
                    "operation" to "team_repositories", "dependency" to "whodis", "team" to teamSlug
                )
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
        summary.repositoriesTotal = repoToTeams.size

        // 3. For each unique repo, fetch vulnerability alerts and send them to the backend
        for ((nameWithOwner, owningTeams) in repoToTeams) {
            val parts = nameWithOwner.split("/")
            if (parts.size != 2) {
                summary.repositoriesFailed++
                logger.logEvent(
                    Level.WARN, "Invalid repository format; repository update not sent",
                    "repository" to nameWithOwner, "operation" to "vulnerability_alerts"
                )
                continue
            }
            val (owner, repo) = parts

            val alerts = try {
                gitHub.vulnerabilityAlertsFor(owner, repo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                summary.repositoriesFailed++
                logger.logFailure(
                    "Vulnerability collection incomplete; repository update not sent", e,
                    "repository" to nameWithOwner, "operation" to "vulnerability_alerts"
                )
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
                summary.repositoriesSent++
                summary.alertsSent += vulnerabilities.size
                logger.logEvent(
                    Level.DEBUG, "Sent vulnerability data",
                    "repository" to nameWithOwner, "alert_count" to vulnerabilities.size
                )
            } else {
                summary.repositoriesFailed++
            }
        }
    }

    private class CollectionSummary {
        var repositoriesTotal = 0
        var repositoriesSent = 0
        var repositoriesFailed = 0
        var teamsFailed = 0
        var alertsSent = 0
    }
}
