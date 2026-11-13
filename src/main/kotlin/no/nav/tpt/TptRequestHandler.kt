package no.nav.tpt

import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.CancellationException
import no.nav.checks.CheckResultsForRepo
import no.nav.checks.Checks
import no.nav.github.GitHub
import no.nav.metrics.TPTMetrics
import no.nav.logging.logFailure

class TptRequestHandler(private val gitHub: GitHub, private val checks: Checks, private val backend: TptBackend) {
    val logger = KtorSimpleLogger(this::class.java.name)

    suspend fun runAllChecksFor(teamSlug: String) {
        gitHub.allReposForTeam(teamSlug).forEach { repo ->
            val allFilesInRepo = try {
                gitHub.allFilePathsIn(repo)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                logger.logFailure(
                    "Complete file inventory unavailable; repository checks not run", ex,
                    "operation" to "file_inventory", "repository" to repo, "team" to teamSlug
                )
                return@forEach
            }
            val checkResults = checks.runAll(repo, allFilesInRepo.toSet())
            if (backend.sendCheckResults(CheckResultsForRepo(repo, listOf(teamSlug), checkResults))) {
                TPTMetrics.msgsSentToTpt(1)
            }
        }
    }
}
