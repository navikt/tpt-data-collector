package no.nav.github

import io.ktor.util.logging.KtorSimpleLogger
import no.nav.whodis.Whodis
import no.nav.checks.CheckResultsForRepo
import no.nav.checks.Checks
import no.nav.metrics.TPTMetrics
import no.nav.tpt.TptBackend
import kotlinx.coroutines.CancellationException
import no.nav.logging.logEvent
import no.nav.logging.logFailure
import org.slf4j.event.Level

class GithubWebhookHandler(val checks: Checks, val backend: TptBackend, val whodis: Whodis, private val organization: String) {
    val logger = KtorSimpleLogger(this::class.java.name)

    suspend fun handle(webhookPayload: WebhookPayload, deliveryId: String? = null) {
        TPTMetrics.webhookReceived()
        val nameWithOwner = webhookPayload.repository.nameWithOwner
        val ignoreReason = ignoreReason(webhookPayload)
        if (ignoreReason != null) {
            logger.logEvent(
                Level.INFO, "GitHub webhook ignored",
                "operation" to "github_webhook", "outcome" to "ignored", "reason" to ignoreReason,
                "repository" to nameWithOwner, "ref" to webhookPayload.ref, "delivery_id" to deliveryId
            )
            return
        }
        logger.logEvent(
            Level.INFO, "GitHub webhook accepted",
            "operation" to "github_webhook", "outcome" to "scheduled",
            "repository" to nameWithOwner, "ref" to webhookPayload.ref, "delivery_id" to deliveryId
        )
        val changedFiles: Set<String> = webhookPayload.commits.flatMap { it.added + it.modified + it.removed }.toSet()
        val repoOwners = try {
            TPTMetrics.whodisLookups(1)
            whodis.ownerTeamsFor(nameWithOwner).also { owners ->
                if (owners.isEmpty()) {
                    logger.logEvent(Level.INFO, "No owning team found for repository", "repository" to nameWithOwner)
                }
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.logFailure(
                "Repository owner lookup failed; publishing checks without owners", ex,
                "operation" to "repository_owners", "dependency" to "whodis", "repository" to nameWithOwner
            )
            emptyList()
        }
        val results = checks.runAll(nameWithOwner, changedFiles, deliveryId)
        val resultsForRepo = CheckResultsForRepo(nameWithOwner, repoOwners, results)
        if (backend.sendCheckResults(resultsForRepo)) {
            TPTMetrics.msgsSentToTpt(1)
        }
    }

    private fun ignoreReason(payload: WebhookPayload): String? {
        if (!payload.repository.nameWithOwner.startsWith("$organization/", ignoreCase = true)) {
            return "other_organization"
        }
        val pushBranch = payload.ref.split("/").last()
        return if (pushBranch != payload.repository.masterBranch) "non_default_branch" else null
    }
}
