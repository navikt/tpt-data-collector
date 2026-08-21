package no.nav.tpt

import kotlinx.serialization.json.Json
import no.nav.checks.CheckResultsForRepo
import no.nav.checks.Checks
import no.nav.github.GitHub
import no.nav.kafka.KafkaSenderInterface
import no.nav.metrics.TPTMetrics

class TptRequestHandler(private val gitHub: GitHub, private val checks: Checks, private val kafka: KafkaSenderInterface) {

    suspend fun runAllChecksFor(teamSlug: String) =
        gitHub.allReposForTeam(teamSlug).map { repo ->
            val allFilesInRepo = gitHub.allFilePathsIn(repo)
            val checkResults = checks.runAll(repo, allFilesInRepo.toSet())
            CheckResultsForRepo(repo, listOf(teamSlug), checkResults)
        }.forEach {
            kafka.sendToKafka("CheckResult", Json.encodeToString(it))
            TPTMetrics.msgsSentToTpt(1)
        }

}