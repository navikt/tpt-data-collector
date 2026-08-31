package no.nav.tpt

import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.serialization.json.Json
import no.nav.checks.CheckResultsForRepo
import no.nav.checks.Checks
import no.nav.github.GitHub
import no.nav.kafka.KafkaSenderInterface
import no.nav.metrics.TPTMetrics

class TptRequestHandler(private val gitHub: GitHub, private val checks: Checks, private val kafka: KafkaSenderInterface) {
    val logger = KtorSimpleLogger(this::class.java.name)

    suspend fun runAllChecksFor(teamSlug: String) {
        gitHub.allReposForTeam(teamSlug).map { repo ->
            // GitHub returns 409 instead of an empty list if there are no files in a repo
            val allFilesInRepo = try {
                gitHub.allFilePathsIn(repo)
            } catch (ex: Exception) {
                logger.warn("Error while listing files in GitHub repo $repo", ex)
                emptyList()
            }
            val checkResults = checks.runAll(repo, allFilesInRepo.toSet())
            CheckResultsForRepo(repo, listOf(teamSlug), checkResults)
        }.forEach { checkResultForRepo ->
            kafka.sendToKafka("CheckResult", Json.encodeToString(checkResultForRepo))
            TPTMetrics.msgsSentToTpt(1)
        }
    }
}
