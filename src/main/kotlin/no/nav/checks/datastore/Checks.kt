package no.nav.checks.datastore

import kotlin.time.Clock
import kotlin.time.Instant
import no.nav.checks.AllGood
import no.nav.checks.CheckResult
import no.nav.checks.NeedsWork
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
            NeedsWork(name, repo,
                result.map { "$repo is running in a non-rootless container named $it" },
                now)
        } else {
            AllGood(name, repo, now)
        }
    }
}