package no.nav.github

import io.ktor.util.logging.KtorSimpleLogger
import no.nav.checks.CheckResult
import no.nav.checks.NeedsWork
import no.nav.checks.datastore.RootImageCheck
import no.nav.checks.repo.ChainguardBaseImageCheck
import no.nav.checks.repo.UnpinnedActionVersionsCheck
import no.nav.datastore.Datastore
import no.nav.metrics.TPTMetrics

class GithubWebhookHandler(val gitHub: GitHub, datastore: Datastore) {
    val logger = KtorSimpleLogger(this::class.java.name)

    private val fileBasedChecks = listOf(ChainguardBaseImageCheck(), UnpinnedActionVersionsCheck())

    private val datastoreBasedChesks = listOf(RootImageCheck(datastore))

    suspend fun handleWebhookEvent(webhookPayload: WebhookPayload) {
        TPTMetrics.webhookReceived()
        logger.info("'${webhookPayload.repository.name}' had a push to push to '${webhookPayload.ref}'")
        if (!isRelevant(webhookPayload)) {
            logger.warn("Skipping checks for '${webhookPayload.repository.name}, it is not relevant'")
            return
        }
        val checkResults = runFileBasedChecks(webhookPayload) + runDatastoreBasedChecks(webhookPayload)
        logger.info("Ran ${checkResults.size} checks for '${webhookPayload.repository}, " +
                "found ${checkResults.filterIsInstance<NeedsWork>().size} things to fix'")
    }

    private fun isRelevant(payload: WebhookPayload): Boolean {
        val pushBranch = payload.ref.split("/").last()
        return payload.repository.fullName.startsWith("navikt/")
                && pushBranch == payload.repository.masterBranch
    }

    private suspend fun runFileBasedChecks(webhookPayload: WebhookPayload): List<CheckResult> {
        val changedFiles: Set<String> = webhookPayload.commits.flatMap { it.added + it.modified }.toSet()
        val filesNeededByChecks =
            fileBasedChecks.flatMap { it.filesICareAbout(changedFiles) }.toSet()
        if (filesNeededByChecks.isEmpty()) {
            logger.info("No file based checks to run for '${webhookPayload.repository.name}'")
            return emptyList()
        }

        val results = try {
            val allFilesWeNeed = filesNeededByChecks.associateWith {
                gitHub.readFileContents(webhookPayload.repository.name, it)
            }
            logger.info("Read the contents of ${allFilesWeNeed.size} files")
            fileBasedChecks.map { check ->
                val filesNeededForThisCheck =
                    check.filesICareAbout(allFilesWeNeed.keys).toSet()
                check.run(webhookPayload.repository.name,allFilesWeNeed.filterKeys { filesNeededForThisCheck.contains(it) })
            }
        } catch (ex: Exception) {
            logger.error("Error while running file based checks", ex)
            TPTMetrics.checkFailed()
            emptyList()
        }
        return results
    }

    private fun runDatastoreBasedChecks(webhookPayload: WebhookPayload): List<CheckResult> {
        val results = try {
            datastoreBasedChesks.map { check ->
                check.run(webhookPayload.repository.name,)
            }
        } catch (ex: Exception) {
            logger.error("Error while running datastore based checks", ex)
            TPTMetrics.checkFailed()
            emptyList()
        }
        return results
    }

}
