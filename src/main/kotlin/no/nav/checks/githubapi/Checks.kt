package no.nav.checks.githubapi

import kotlin.time.Clock
import no.nav.checks.CheckResult
import no.nav.checks.Severity
import no.nav.checks.Severity.HIGH
import no.nav.github.GitHub

interface GitHubApiBasedCheck {
    suspend fun run(repo: String): CheckResult
}

class CriticalVulnerabilitiesCheck(val gitHub: GitHub) : GitHubApiBasedCheck {
    private val name = "HasCriticalVulns"
    private val desc = "Crtitical vulnerabilities should be handled asap."
    private val severity = HIGH

    override suspend fun run(repo: String): CheckResult {
        val now = Clock.System.now()
        val nrOfCriticalVulns = gitHub.dependabotSecurityAlertsFor(repo)
            .count { (_, severity) -> severity == "critical" }
        return if (nrOfCriticalVulns > 0) {
            CheckResult.NeedsWork(name,desc, severity, now,
                listOf("$repo has $nrOfCriticalVulns critical vulnerabilities")
            )
        } else {
            CheckResult.AllGood(name, desc, severity, now)
        }
    }
}

class GithubToolingStatusCheck(val gitHub: GitHub) : GitHubApiBasedCheck {
    private val name = "GitHubToolingStatus"
    private val desc = "GitHub security tooling enables discovery of security problems."
    private val severity = Severity.MEDIUM

    override suspend fun run(repo: String): CheckResult {
        val now = Clock.System.now()
        val latestToolConfigResults = gitHub.latestCodeScanningAnalysesFor(repo)
        if (latestToolConfigResults.isEmpty()) {
            // No code scanning enabled
            return CheckResult.NeedsWork(name, desc, severity,now,
                listOf("$repo has no code scanning analyses, possibly no tools configured"))
        }
        if (latestToolConfigResults.any { it.error.isNotEmpty() }) {
            val errors = latestToolConfigResults.filter { it.error.isNotEmpty() }.map { it.error }
            return CheckResult.NeedsWork(name, desc, severity, now,
                listOf("$repo has code scanning analyses with errors: ${errors.joinToString(", ")}"))
        }
        else {
            return CheckResult.AllGood(name, desc, severity, now)
        }
    }
}