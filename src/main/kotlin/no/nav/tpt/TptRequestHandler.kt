package no.nav.tpt

import io.ktor.util.logging.KtorSimpleLogger
import no.nav.checks.CheckResultsForRepo
import no.nav.checks.Checks
import no.nav.github.GitHub
import no.nav.metrics.TPTMetrics

class TptRequestHandler(private val gitHub: GitHub, private val checks: Checks, private val backend: TptBackend) {
    val logger = KtorSimpleLogger(this::class.java.name)

    suspend fun runAllChecksFor(teamSlug: String) {
        gitHub.allReposForTeam(teamSlug).forEach { repo ->
            // GitHub returns 409 instead of an empty list if there are no files in a repo
            val allFilesInRepo = try {
                gitHub.allFilePathsIn(repo)
            } catch (ex: Exception) {
                logger.warn("Error while listing files in GitHub repo $repo", ex)
                emptyList()
            }
            val checkResults = checks.runAll(repo, allFilesInRepo.toSet())
            if (backend.sendCheckResults(CheckResultsForRepo(repo, listOf(teamSlug), checkResults))) {
                TPTMetrics.msgsSentToTpt(1)
            }
        }
    }
}
