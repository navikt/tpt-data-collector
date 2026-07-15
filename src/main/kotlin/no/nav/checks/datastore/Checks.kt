package no.nav.checks.datastore

import kotlin.time.Clock
import no.nav.checks.CheckResult
import no.nav.datastore.Datastore

interface DatastoreBasedCheck {
    fun run(repo: String): CheckResult
}

class RootImageCheck(val datastore: Datastore): DatastoreBasedCheck {
    private val name = "RootlessContainer"

    override fun run(repo: String): CheckResult {
        val now = Clock.System.now()
        val result = datastore.containersAbleToRunAsRoot(repo)
        return if (result.isNotEmpty()) {
            CheckResult.NeedsWork(
                name, repo,
                now,
                result.map { "$repo is running in a non-rootless container named $it" }
            )
        } else {
            CheckResult.AllGood(name, repo, now)
        }
    }
}