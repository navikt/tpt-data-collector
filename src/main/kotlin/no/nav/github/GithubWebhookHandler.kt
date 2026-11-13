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

    suspend fun handle(webhookPayload: WebhookPayload) {
        TPTMetrics.webhookReceived()
        val repo = webhookPayload.repository.name
        if (!isRelevant(webhookPayload)) {
            logger.logEvent(
                Level.DEBUG, "Push ignored; not in the configured organization or default branch",
                "repository" to webhookPayload.repository.fullName, "ref" to webhookPayload.ref
            )
            return
        }
        logger.logEvent(
            Level.DEBUG, "Processing default-branch push",
            "repository" to webhookPayload.repository.fullName, "ref" to webhookPayload.ref
        )
        val changedFiles: Set<String> = webhookPayload.commits.flatMap { it.added + it.modified }.toSet()
        val repoOwners = try {
            TPTMetrics.whodisLookups(1)
            whodis.ownerTeamsFor(repo).also { owners ->
                if (owners.isEmpty()) {
                    logger.logEvent(Level.INFO, "No owning team found for repository", "repository" to repo)
                }
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.logFailure(
                "Repository owner lookup failed; publishing checks without owners", ex,
                "operation" to "repository_owners", "dependency" to "whodis", "repository" to repo
            )
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
        return payload.repository.fullName.startsWith("$organization/", ignoreCase = true) && pushBranch == payload.repository.masterBranch
    }
}
