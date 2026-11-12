package no.nav.checks.githubapi

import io.ktor.util.logging.KtorSimpleLogger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import no.nav.checks.CheckResult
import no.nav.checks.Severity
import no.nav.checks.Severity.HIGH
import no.nav.github.DependabotAlertsResult
import no.nav.github.GitHub

interface GitHubApiBasedCheck {
    /** Returns null when an expected unavailable capability prevents assessment. */
    suspend fun run(nameWithOwner: String): CheckResult?
}

class CriticalVulnerabilitiesCheck(val gitHub: GitHub) : GitHubApiBasedCheck {
    private val name = "HasCriticalVulns"
    private val desc = "Crtitical vulnerabilities should be handled asap."
    private val severity = HIGH
    private val logger = KtorSimpleLogger(this::class.java.name)

    override suspend fun run(nameWithOwner: String): CheckResult? {
        val hasCriticalAlerts = when (val result = gitHub.hasOpenCriticalDependabotAlerts(nameWithOwner)) {
            is DependabotAlertsResult.Available -> result.hasOpenCriticalAlerts
            DependabotAlertsResult.Disabled -> {
                logger.info("Skipping $name for $nameWithOwner: Dependabot alerts are disabled")
                return null
            }
        }
        val now = Clock.System.now()
        return if (hasCriticalAlerts) {
            CheckResult.NeedsWork(name,desc, severity, now,
                listOf("GitHub reports open critical vulnerabilities in $nameWithOwner")
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

    override suspend fun run(nameWithOwner: String): CheckResult {
        val now = Clock.System.now()
        val latestToolConfigResults = gitHub.latestCodeScanningAnalysesFor(nameWithOwner)
        if (latestToolConfigResults.isEmpty()) {
            // No code scanning enabled
            return CheckResult.NeedsWork(name, desc, severity,now,
                listOf("$nameWithOwner has no code scanning analyses, possibly no tools configured"))
        }
        if (latestToolConfigResults.any { it.error.isNotEmpty() }) {
            val errors = latestToolConfigResults.filter { it.error.isNotEmpty() }.map { it.error }
            return CheckResult.NeedsWork(name, desc, severity, now,
                listOf("$nameWithOwner has code scanning analyses with errors: ${errors.joinToString(", ")}"))
        }
        else {
            return CheckResult.AllGood(name, desc, severity, now)
        }
    }
}

class NewestCommitCheck(val gitHub: GitHub) : GitHubApiBasedCheck {
    private val name = this.javaClass.simpleName
    private val desc = "Commit security updates for dependencies regularly even if there are no changes in your own code"
    private val severity = Severity.MEDIUM

    override suspend fun run(nameWithOwner: String): CheckResult {
        val now = Clock.System.now()
        val newestCommitTime = gitHub.latestCommitTimeFor(nameWithOwner)

        val daysSinceLastCommit =  now - newestCommitTime
        if (daysSinceLastCommit > 30.days) {
            return CheckResult.NeedsWork(name, desc, severity,now,
                listOf("'$nameWithOwner' hasn't seen a commit in ${daysSinceLastCommit.inWholeDays} days"))
        }

        return CheckResult.AllGood(name, desc, severity, now)
    }
}