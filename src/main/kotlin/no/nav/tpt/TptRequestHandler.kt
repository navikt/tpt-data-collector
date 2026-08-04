package no.nav.tpt

import kotlinx.serialization.json.Json
import no.nav.checks.Checks
import no.nav.github.GitHub
import no.nav.kafka.KafkaSenderInterface
import no.nav.metrics.TPTMetrics

class TptRequestHandler(private val gitHub: GitHub, private val checks: Checks, private val kafka: KafkaSenderInterface) {

    suspend fun runAllChecksFor(teamSlug: String) {
        val repos = gitHub.allReposForTeam(teamSlug)
        val results =  repos.flatMap { repo ->
            val allFilesInRepo = gitHub.allFilePathsIn(repo)
            checks.runAll(repo, allFilesInRepo.toSet())
        }
        kafka.sendToKafka("CheckResult", Json.encodeToString(results))
        TPTMetrics.msgsSentToTpt(1)
    }

}