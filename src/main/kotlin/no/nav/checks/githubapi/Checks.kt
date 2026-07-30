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
        val toolingStatus = gitHub.latestCodeScanningAnalysesFor(repo)
        return if (toolingStatus != "ok") {
            CheckResult.NeedsWork(
                name, repo,
                now,
                listOf("$repo has a tooling status of $toolingStatus")
            )
        } else {
            CheckResult.AllGood(name, repo, now)
        }
    }
}