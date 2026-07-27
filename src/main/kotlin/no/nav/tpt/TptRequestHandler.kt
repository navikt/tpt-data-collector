package no.nav.tpt

import no.nav.checks.CheckResult
import no.nav.checks.Checks
import no.nav.github.GitHub

class TptRequestHandler(private val gitHub: GitHub, private val checks: Checks) {

    suspend fun runAllChecksFor(teamSlug: String): List<CheckResult> {
        val repos = gitHub.allReposForTeam(teamSlug)
        return repos.flatMap { repo ->
            val allFilesInRepo = gitHub.allFilePathsIn(repo)
            checks.runAll(repo, allFilesInRepo.toSet())
        }
    }

}