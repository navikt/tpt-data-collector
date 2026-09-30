package no.nav.github

import io.ktor.util.logging.KtorSimpleLogger
import no.nav.whodis.Whodis
import no.nav.checks.CheckResultsForRepo
import no.nav.checks.Checks
import no.nav.metrics.TPTMetrics
import no.nav.tpt.TptBackend

class GithubWebhookHandler(val checks: Checks, val backend: TptBackend, val whodis: Whodis) {
    val logger = KtorSimpleLogger(this::class.java.name)

    suspend fun handle(webhookPayload: WebhookPayload) {
        TPTMetrics.webhookReceived()
        val repo = webhookPayload.repository.name
        logger.info("'$repo' had a push to push to '${webhookPayload.ref}'")
        if (!isRelevant(webhookPayload)) {
            logger.info("Skipping checks for '$repo, it is not relevant'")
            return
        }
        val changedFiles: Set<String> = webhookPayload.commits.flatMap { it.added + it.modified }.toSet()
        val repoOwners = try {
            TPTMetrics.whodisLookups(1)
            whodis.ownerTeamsFor(repo)
        } catch(ex: Exception) {
            logger.error("Error while finding owners for '$repo'", ex)
            emptyList()
        }
        val results = checks.runAll(webhookPayload.repository.name, changedFiles)
        val resultsForRepo = CheckResultsForRepo(repo, repoOwners, results)
        if (backend.sendCheckResults(resultsForRepo)) {
            TPTMetrics.msgsSentToTpt(1)
        }
    }

    private fun isRelevant(payload: WebhookPayload): Boolean {
        val pushBranch = payload.ref.split("/").last()
        return payload.repository.fullName.startsWith("navikt/") && pushBranch == payload.repository.masterBranch
    }
}
