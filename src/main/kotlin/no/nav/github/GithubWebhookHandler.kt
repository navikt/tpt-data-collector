package no.nav.github

import io.ktor.util.logging.KtorSimpleLogger
import no.nav.checks.repo.RepoBasedCheck
import no.nav.metrics.TPTMetrics

class GithubWebhookHandler(val repoChecks: List<RepoBasedCheck>, val ghFileLoader: GithubRepositoryContentsClientInterface) {
    val logger = KtorSimpleLogger(this::class.java.name)

    fun handleWebhookEvent(webhookPayload: WebhookPayload) {
        TPTMetrics.countWebhook()
        logger.info("'${webhookPayload.repository.name}' had a push to push to '${webhookPayload.ref}'")
        if (!isRelevant(webhookPayload)) {
            logger.warn("Skipping checks for '${webhookPayload.repository.name}, it is not relevant'")
            return
        }
        val changedFiles: Set<String> = addedAndModifiedFiles(webhookPayload)

        val filesNeededByChecks =
            repoChecks.flatMap { it.filesICareAbout(changedFiles) }.toSet()

        val fileContents = filesNeededByChecks.associateWith {
            ghFileLoader.readFile("navikt", webhookPayload.repository.name, it, webhookPayload.commits[0].id)
        }

        logger.info("Read the contents of ${fileContents.keys.size} files")
    }

    private fun addedAndModifiedFiles(payload: WebhookPayload): Set<String> {
        return payload.commits.flatMap { it.added + it.modified }.toSet()
    }

    private fun isRelevant(payload: WebhookPayload): Boolean {
        val pushBranch = payload.ref.split("/").last()
        return payload.repository.fullName.startsWith("navikt/")
                && pushBranch == payload.repository.masterBranch
    }

}
