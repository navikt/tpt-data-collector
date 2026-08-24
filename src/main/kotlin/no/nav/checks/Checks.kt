package no.nav.checks

import io.ktor.util.logging.KtorSimpleLogger
import kotlin.time.measureTimedValue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import no.nav.checks.datastore.OldDeploymentsCheck
import no.nav.checks.files.CopyDotDotCheck
import no.nav.checks.files.BaseImageCheck
import no.nav.checks.files.PwnRequestCheck
import no.nav.checks.files.UnpinnedActionVersionsCheck
import no.nav.checks.githubapi.CriticalVulnerabilitiesCheck
import no.nav.checks.githubapi.GithubToolingStatusCheck
import no.nav.datastore.Datastore
import no.nav.github.GitHub
import no.nav.metrics.TPTMetrics

class Checks(val gitHub: GitHub, datastore: Datastore) {
    val logger = KtorSimpleLogger(this::class.java.name)

    private val fileBasedChecks = listOf(
        BaseImageCheck(), UnpinnedActionVersionsCheck(),
        CopyDotDotCheck(), PwnRequestCheck()
    )
    private val datastoreBasedChecks = listOf(OldDeploymentsCheck(datastore))
    private val gitHubAPIBasedChecks = listOf(
        CriticalVulnerabilitiesCheck(gitHub),
        GithubToolingStatusCheck(gitHub)
    )

    suspend fun runAll(repoName: String, relevantFiles: Set<String>): List<CheckResult> {
        val timedResults = mapOf(
            "Files" to measureTimedValue { runFileBasedChecks(repoName, relevantFiles).awaitAll() },
            "Datastore" to measureTimedValue { runDatastoreBasedChecks(repoName).awaitAll() },
            "GitHubApi" to measureTimedValue { runGitHubAPIBasedChecks(repoName).awaitAll() }
        )

        val allResults = timedResults.values.flatMap { it.value }
        val successfulResults = allResults.mapNotNull { it.getOrNull() }
        val failedResults = allResults.filter { it.isFailure }
        if (failedResults.isNotEmpty()) {
            logger.warn(
                "Failures during checks: ${
                    failedResults.mapNotNull { it.exceptionOrNull() }.joinToString(" -- ")
                }"
            )
        }
        val nrOfIssuesFound = successfulResults.count { it is CheckResult.NeedsWork }
        logger.info("Ran ${allResults.size} checks for '$repoName, ${failedResults.size} of them failed")
        TPTMetrics.checkFailed(failedResults.size)
        TPTMetrics.issuesFound(nrOfIssuesFound)
        timedResults.forEach { (checkType, v) -> TPTMetrics.checksRanIn(checkType, v.duration) }

        return successfulResults
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
                async { gitHub.readFileContents(repoName, it) }.await()
            }
            logger.info("Read the contents of ${allFilesWeNeed.size} file(s)")

            fileBasedChecks.filter { it.filesICareAbout(allFilesWeNeed.keys).toSet().isNotEmpty() }
                .map { check ->
                    val  filesNeededForThisCheck = check.filesICareAbout(allFilesWeNeed.keys).toSet()
                    async {
                        runCatching {
                            check.run(
                                repoName,
                                allFilesWeNeed.filterKeys { filesNeededForThisCheck.contains(it) })
                        }
                    }
                }

        }

    private suspend fun runDatastoreBasedChecks(repoName: String): List<Deferred<Result<CheckResult>>> =
        coroutineScope {
            datastoreBasedChecks.map { check ->
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