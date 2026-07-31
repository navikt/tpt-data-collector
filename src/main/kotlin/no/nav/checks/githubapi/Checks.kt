package no.nav.checks.githubapi

import kotlin.time.Clock
import no.nav.checks.CheckResult
import no.nav.github.GitHub

interface GitHubApiBasedCheck {
    suspend fun run(repo: String): CheckResult
}

class CriticalVulnerabilitiesCheck(val gitHub: GitHub) : GitHubApiBasedCheck {
    private val name = "HasCriticalVulns"

    override suspend fun run(repo: String): CheckResult {
        val now = Clock.System.now()
        val nrOfCriticalVulns = gitHub.dependabotSecurityAlertsFor(repo)
            .count { (_, severity) -> severity == "critical" }
        return if (nrOfCriticalVulns > 0) {
            CheckResult.NeedsWork(
                name, repo,
                now,
                listOf("$repo has $nrOfCriticalVulns critical vulnerabilities")
            )
        } else {
            CheckResult.AllGood(name, repo, now)
        }
    }
}

class GithubToolingStatusCheck(val gitHub: GitHub) : GitHubApiBasedCheck {
    private val name = "githubToolingStatus"

    override suspend fun run(repo: String): CheckResult {
        val now = Clock.System.now()
        val latestToolConfigResults = gitHub.latestCodeScanningAnalysesFor(repo)
        if (latestToolConfigResults.isEmpty()) {
            // No code scanning enabled
            return CheckResult.NeedsWork(name, repo, now, listOf("$repo has no code scanning analyses, possibly no tools configured"))
        }
        if (latestToolConfigResults.any { it.error.isNotEmpty() }) {
            val errors = latestToolConfigResults.filter { it.error.isNotEmpty() }.map { it.error }
            return CheckResult.NeedsWork(name, repo, now, listOf("$repo has code scanning analyses with errors: ${errors.joinToString(", ")}"))
        }
        else {
            return CheckResult.AllGood(name, repo, now)
        }
    }
}