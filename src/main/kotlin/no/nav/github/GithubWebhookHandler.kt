package no.nav.github

import io.ktor.util.logging.KtorSimpleLogger
import no.nav.checks.CheckResult
import no.nav.checks.repo.RepoBasedCheck
import no.nav.metrics.TPTMetrics

class GithubWebhookHandler(val repoChecks: List<RepoBasedCheck>, val ghFileLoader: GithubRepositoryContentsClientInterface) {
    val logger = KtorSimpleLogger(this::class.java.name)

    fun handleWebhookEvent(webhookPayload: WebhookPayload) {
        TPTMetrics.webhookReceived()
        logger.info("'${webhookPayload.repository.name}' had a push to push to '${webhookPayload.ref}'")
        if (!isRelevant(webhookPayload)) {
            logger.warn("Skipping checks for '${webhookPayload.repository.name}, it is not relevant'")
            return
        }
        val repoResults = runRepoBasedChecks(webhookPayload, repoChecks)
        logger.info("Ran ${repoResults.size} repo based checks for '${webhookPayload.repository}'")
    }

    private fun isRelevant(payload: WebhookPayload): Boolean {
        val pushBranch = payload.ref.split("/").last()
        return payload.repository.fullName.startsWith("navikt/")
                && pushBranch == payload.repository.masterBranch
    }

    private fun runRepoBasedChecks(webhookPayload: WebhookPayload, checks: List<RepoBasedCheck>): List<CheckResult> {
        val changedFiles: Set<String> = webhookPayload.commits.flatMap { it.added + it.modified }.toSet()
        val filesNeededByChecks =
            repoChecks.flatMap { it.filesICareAbout(changedFiles) }.toSet()
        if (filesNeededByChecks.isEmpty()) {
            logger.info("No repo based checks to run for '${webhookPayload.repository.name}'")
            return emptyList()
        }

        val results = try {
            val allFilesWeNeed = filesNeededByChecks.associateWith {
                ghFileLoader.readFile("navikt", webhookPayload.repository.name, it, webhookPayload.commits[0].id)
            }
            logger.info("Read the contents of ${allFilesWeNeed.size} files")
            checks.map { check ->
                val filesNeededForThisCheck =
                    check.filesICareAbout(allFilesWeNeed.keys).toSet()
                check.run(webhookPayload.repository.name,allFilesWeNeed.filterKeys { filesNeededForThisCheck.contains(it) })
            }
        } catch (ex: Exception) {
            logger.error("Error while running repo based checks", ex)
            TPTMetrics.checkFailed()
            emptyList()
        }
        return results
    }

}
