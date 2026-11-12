package no.nav.github

import io.ktor.util.logging.KtorSimpleLogger
import no.nav.whodis.Whodis
import no.nav.checks.CheckResultsForRepo
import no.nav.checks.Checks
import no.nav.metrics.TPTMetrics
import no.nav.tpt.TptBackend

class GithubWebhookHandler(val checks: Checks, val backend: TptBackend, val whodis: Whodis, private val organization: String) {
    val logger = KtorSimpleLogger(this::class.java.name)

    suspend fun handle(webhookPayload: WebhookPayload) {
        TPTMetrics.webhookReceived()
        val nameWithOwner = webhookPayload.repository.nameWithOwner
        logger.info("'$nameWithOwner' had a push to '${webhookPayload.ref}'")
        if (!isRelevant(webhookPayload)) {
            logger.debug("Skipping checks for '$nameWithOwner', it is not relevant")
            return
        }
        val changedFiles: Set<String> = webhookPayload.commits.flatMap { it.added + it.modified }.toSet()
        val repoOwners = try {
            TPTMetrics.whodisLookups(1)
            whodis.ownerTeamsFor(nameWithOwner)
        } catch(ex: Exception) {
            logger.error("Error while finding owners for '$nameWithOwner'", ex)
            emptyList()
        }
        val results = checks.runAll(nameWithOwner, changedFiles)
        val resultsForRepo = CheckResultsForRepo(nameWithOwner, repoOwners, results)
        if (backend.sendCheckResults(resultsForRepo)) {
            TPTMetrics.msgsSentToTpt(1)
        }
    }

    private fun isRelevant(payload: WebhookPayload): Boolean {
        val pushBranch = payload.ref.split("/").last()
        return payload.repository.nameWithOwner.startsWith("$organization/", ignoreCase = true) && pushBranch == payload.repository.masterBranch
    }
}
