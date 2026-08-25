package no.nav.checks.datastore

import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import no.nav.checks.CheckResult
import no.nav.checks.Severity.MEDIUM
import no.nav.datastore.Datastore

interface DatastoreBasedCheck {
    fun run(repo: String): CheckResult
}

class OldDeploymentsCheck(val datastore: Datastore): DatastoreBasedCheck {
    private val name = "OldDeployments"
    private val desc = "Deployments should be refreshed regularly to get security updates."
    private val severity = MEDIUM

    override fun run(repo: String): CheckResult {
        val now = Clock.System.now()
        val ninetyDaysAgo = now - 90.days
        val outdatedDeployments =
            datastore.activeDeploymentsFor( repo)
                .filter { (_, _, deployTime) -> isOlderThan(deployTime, ninetyDaysAgo) }
        return if (outdatedDeployments.isNotEmpty()) {
            CheckResult.NeedsWork(
                name,
                desc,
                severity,
                now,
                outdatedDeployments.map { "${it.first} is running an old (${it.third}) deployment of $repo in ${it.second}" }
            )
        } else {
            CheckResult.AllGood(name, desc, severity, now)
        }
    }
}

internal fun isOlderThan(timeToCheck: Instant, cutoff: Instant): Boolean {
    return timeToCheck < cutoff
}