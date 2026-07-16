package no.nav.checks.datastore

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.time.toKotlinInstant
import no.nav.checks.CheckResult
import no.nav.datastore.Datastore

interface DatastoreBasedCheck {
    fun run(repo: String): CheckResult
}

class OldDeploymentsCheck(val datastore: Datastore): DatastoreBasedCheck {
    private val name = "OldDeployments"

    override fun run(repo: String): CheckResult {
        val now = Clock.System.now()
        val ninetyDaysAgo = now - 90.days
        val outdatedDeployments =
            datastore.activeDeploymentsFor( repo)
                .filter { (_, _, deployTime) -> isOlderThan(deployTime, ninetyDaysAgo) }
        return if (outdatedDeployments.isNotEmpty()) {
            CheckResult.NeedsWork(
                name, repo,
                now,
                outdatedDeployments.map { "${it.first} is running an old (${it.third}) deployment of $repo in ${it.second}" }
            )
        } else {
            CheckResult.AllGood(name, repo, now)
        }
    }
}

internal fun isOlderThan(ldt: LocalDateTime, cutoff: Instant): Boolean {
    val javaInstant: java.time.Instant = ldt.atZone(ZoneId.systemDefault()).toInstant()
    val kotlinInstant: Instant = javaInstant.toKotlinInstant()
    return kotlinInstant < cutoff
}