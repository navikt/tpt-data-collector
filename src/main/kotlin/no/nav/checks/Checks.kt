package no.nav.checks

import io.ktor.util.logging.KtorSimpleLogger
import kotlin.time.measureTimedValue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import no.nav.checks.datastore.OldDeploymentsCheck
import no.nav.checks.files.ChainguardBaseImageCheck
import no.nav.checks.files.CopyDotDotCheck
import no.nav.checks.files.UnpinnedActionVersionsCheck
import no.nav.checks.githubapi.CriticalVulnerabilitiesCheck
import no.nav.datastore.Datastore
import no.nav.github.GitHub
import no.nav.metrics.TPTMetrics

class Checks(val gitHub: GitHub, datastore: Datastore) {
    val logger = KtorSimpleLogger(this::class.java.name)

    private val fileBasedChecks = listOf(ChainguardBaseImageCheck(), UnpinnedActionVersionsCheck(), CopyDotDotCheck())
    private val datastoreBasedChesks = listOf(OldDeploymentsCheck(datastore))
    private val gitHubAPIBasedChecks = listOf(CriticalVulnerabilitiesCheck(gitHub))

    suspend fun runAll(repoName: String, relevantFiles: Set<String>) {
        val timed = measureTimedValue {
            (runFileBasedChecks(repoName, relevantFiles) +
                    runDatastoreBasedChecks(repoName) +
                    runGitHubAPIBasedChecks(repoName)
                    ).awaitAll()
        }
        val failedChecks = timed.value.count { it.isFailure }
        val nrOfIssuesFound =
            timed.value.map { kotlinResult -> kotlinResult.map { it is CheckResult.NeedsWork } }.count()
        logger.info(
            "Ran ${timed.value.size} checks for '$repoName, $failedChecks of them failed in ${timed.duration}"
        )
        TPTMetrics.checksRanIn(timed.duration)
        TPTMetrics.checkFailed(failedChecks)
        TPTMetrics.issuesFound(nrOfIssuesFound)
    }

    private suspend fun runFileBasedChecks(
        repoName: String,
        relevantFiles: Set<String>
    ): List<Deferred<Result<CheckResult>>> =
        coroutineScope {
            val filesNeededByChecks = fileBasedChecks.flatMap { it.filesICareAbout(relevantFiles) }.toSet()
            if (filesNeededByChecks.isEmpty()) {
                logger.info("No file based checks to run for '$repoName'")
                return@coroutineScope emptyList()
            }

            val allFilesWeNeed = filesNeededByChecks.associateWith {
                gitHub.readFileContents(repoName, it)
            }
            logger.info("Read the contents of ${allFilesWeNeed.size} file(s)")

            fileBasedChecks.map { check ->
                async {
                    runCatching {
                        val filesNeededForThisCheck = check.filesICareAbout(allFilesWeNeed.keys).toSet()
                        check.run(
                            repoName,
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