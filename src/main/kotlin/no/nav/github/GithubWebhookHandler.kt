package no.nav.github

import io.ktor.util.logging.KtorSimpleLogger
import kotlin.time.measureTimedValue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import no.nav.checks.CheckResult
import no.nav.checks.datastore.OldDeploymentsCheck
import no.nav.checks.files.ChainguardBaseImageCheck
import no.nav.checks.files.CopyDotDotCheck
import no.nav.checks.files.UnpinnedActionVersionsCheck
import no.nav.checks.githubapi.CriticalVulnerabilitiesCheck
import no.nav.datastore.Datastore
import no.nav.kafka.KafkaSenderInterface
import no.nav.metrics.TPTMetrics

class GithubWebhookHandler(val gitHub: GitHub, datastore: Datastore, val kafka: KafkaSenderInterface) {
    val logger = KtorSimpleLogger(this::class.java.name)

    private val fileBasedChecks = listOf(ChainguardBaseImageCheck(), UnpinnedActionVersionsCheck(), CopyDotDotCheck())
    private val datastoreBasedChesks = listOf(OldDeploymentsCheck(datastore))
    private val gitHubAPIBasedChecks = listOf(CriticalVulnerabilitiesCheck(gitHub))

    suspend fun handleWebhookEvent(webhookPayload: WebhookPayload) {
        TPTMetrics.webhookReceived()
        logger.info("'${webhookPayload.repository.name}' had a push to push to '${webhookPayload.ref}'")
        if (!isRelevant(webhookPayload)) {
            logger.warn("Skipping checks for '${webhookPayload.repository.name}, it is not relevant'")
            return
        }
        val timed = measureTimedValue {
            (
                runFileBasedChecks(webhookPayload) +
                runDatastoreBasedChecks(webhookPayload.repository.name) +
                runGitHubAPIBasedChecks(webhookPayload.repository.name)
            ).awaitAll()
        }
        val successfulChecks = timed.value.count { it.isSuccess }
        val failedChecks = timed.value.count { it.isFailure }
        logger.info(
            "Ran ${timed.value.size} checks for '${webhookPayload.repository.name}, " +
                    "$successfulChecks succeeded and $failedChecks failed in ${timed.duration}"
        )
        TPTMetrics.checksRanIn(timed.duration)
        TPTMetrics.checkFailed(failedChecks)
    }

    private fun isRelevant(payload: WebhookPayload): Boolean {
        val pushBranch = payload.ref.split("/").last()
        return payload.repository.fullName.startsWith("navikt/") && pushBranch == payload.repository.masterBranch
    }

    private suspend fun runFileBasedChecks(webhookPayload: WebhookPayload): List<Deferred<Result<CheckResult>>> =
        coroutineScope {
            val changedFiles: Set<String> = webhookPayload.commits.flatMap { it.added + it.modified }.toSet()
            val filesNeededByChecks = fileBasedChecks.flatMap { it.filesICareAbout(changedFiles) }.toSet()
            if (filesNeededByChecks.isEmpty()) {
                logger.info("No file based checks to run for '${webhookPayload.repository.name}'")
                return@coroutineScope emptyList()
            }

            val allFilesWeNeed = filesNeededByChecks.associateWith {
                gitHub.readFileContents(webhookPayload.repository.name, it)
            }
            logger.info("Read the contents of ${allFilesWeNeed.size} file(s)")

            fileBasedChecks.map { check ->
                async {
                    runCatching {
                        val filesNeededForThisCheck = check.filesICareAbout(allFilesWeNeed.keys).toSet()
                        check.run(
                            webhookPayload.repository.name,
                            allFilesWeNeed.filterKeys { filesNeededForThisCheck.contains(it) })
                    }
                }
            }
        }

    private suspend fun runDatastoreBasedChecks(repoName: String): List<Deferred<Result<CheckResult>>> =
        coroutineScope {
            datastoreBasedChesks.map { check ->
                async { runCatching { check.run(repoName) } }
            }
        }


    private suspend fun runGitHubAPIBasedChecks(repoName: String): List<Deferred<Result<CheckResult>>> =
        coroutineScope {
            gitHubAPIBasedChecks.map { check ->
                async { runCatching { check.run(repoName) } }
            }
        }
}
