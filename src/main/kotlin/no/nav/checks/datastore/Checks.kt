package no.nav.checks.datastore

import no.nav.checks.AllGood
import no.nav.checks.CheckResult
import no.nav.checks.NeedsWork
import no.nav.datastore.Datastore

interface DatastoreBasedCheck {
    fun run(repo: String): CheckResult
}

class RootImageCheck(val datastore: Datastore): DatastoreBasedCheck {
    private val name = "Chainguard base image check"

    override fun run(repo: String): CheckResult {
        val result = datastore.containersAbleToRunAsRoot(repo)
        return if (result.isNotEmpty()) {
            NeedsWork(name, repo, result.map { "$it is running in a non-rootless container" })
        } else {
            AllGood(name, repo)
        }
    }
}