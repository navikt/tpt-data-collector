package no.nav.checks

import io.ktor.util.logging.KtorSimpleLogger
import kotlin.time.measureTimedValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import no.nav.checks.datastore.OldDeploymentsCheck
import no.nav.checks.files.BaseImageIsNotPinnedCheck
import no.nav.checks.files.CopyDotDotCheck
import no.nav.checks.files.DistrolessCheck
import no.nav.checks.files.CurlPipeShellCheck
import no.nav.checks.files.HasGitignoreCheck
import no.nav.checks.files.NpxUsageCheck
import no.nav.checks.files.PwnRequestCheck
import no.nav.checks.files.ReadmeCheck
import no.nav.checks.files.UnpinnedActionVersionsCheck
import no.nav.checks.githubapi.CriticalVulnerabilitiesCheck
import no.nav.checks.githubapi.GithubToolingStatusCheck
import no.nav.checks.githubapi.NewestCommitCheck
import no.nav.datastore.Datastore
import no.nav.github.GitHub
import no.nav.metrics.TPTMetrics
import no.nav.logging.logEvent
import no.nav.logging.logFailure
import org.slf4j.event.Level

class Checks(val gitHub: GitHub, datastore: Datastore) {
    val logger = KtorSimpleLogger(this::class.java.name)

    private val fileBasedChecks = listOf(
        DistrolessCheck(), UnpinnedActionVersionsCheck(), CopyDotDotCheck(),
        PwnRequestCheck(), NpxUsageCheck(), CurlPipeShellCheck(), BaseImageIsNotPinnedCheck(),
        HasGitignoreCheck(), ReadmeCheck()
    )
    private val datastoreBasedChecks = listOf(OldDeploymentsCheck(datastore))
    private val gitHubAPIBasedChecks = listOf(
        CriticalVulnerabilitiesCheck(gitHub), GithubToolingStatusCheck(gitHub),
        NewestCommitCheck(gitHub)
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
        val nrOfIssuesFound = successfulResults.count { it is CheckResult.NeedsWork }
        logger.logEvent(
            Level.INFO, "Repository checks finished",
            "repository" to repoName,
            "outcome" to if (failedResults.isEmpty()) "success" else "partial",
            "checks_attempted" to allResults.size, "checks_completed" to successfulResults.size,
            "checks_failed" to failedResults.size,
            "checks_unavailable" to allResults.size - successfulResults.size - failedResults.size,
            "issues_found" to nrOfIssuesFound
        )
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
            val filesNeededByChecks = fileBasedChecks
                .associateWith { it.filesICareAbout(relevantFiles).toSet() }
                .filterValues { it.isNotEmpty() }
            if (filesNeededByChecks.isEmpty()) {
                logger.logEvent(Level.DEBUG, "No file-based checks to run", "repository" to repoName)
                return@coroutineScope emptyList()
            }

            val allFilesWeNeed = filesNeededByChecks.values.flatten().toSet()
                .intersect(relevantFiles)
                .associateWith { filePath ->
                    async {
                        runCatching { gitHub.readFileContents(repoName, filePath) }
                            .onFailure { ex ->
                                if (ex is CancellationException) throw ex
                                logger.logFailure(
                                    "File read failed; dependent checks omitted", ex,
                                    "operation" to "file_contents", "repository" to repoName, "file" to filePath,
                                    "affected_checks" to filesNeededByChecks.filterValues { filePath in it }
                                        .keys.joinToString(",") { it.javaClass.simpleName }
                                )
                            }
                    }.await()
                }
            logger.logEvent(
                Level.DEBUG, "Repository file reads finished", "repository" to repoName,
                "files_read" to allFilesWeNeed.values.count { it.isSuccess },
                "files_failed" to allFilesWeNeed.values.count { it.isFailure }
            )

            filesNeededByChecks.map { (check, filesNeededForThisCheck) ->
                async {
                    val contents = allFilesWeNeed.filterKeys { it in filesNeededForThisCheck }
                    // A shared read failure is logged once above, with all affected checks.
                    val readFailure = contents.values.firstNotNullOfOrNull { it.exceptionOrNull() }
                    if (readFailure != null) return@async Result.failure<CheckResult>(readFailure)
                    runCheck(repoName, check.javaClass.simpleName) {
                        check.run(
                            repoName,
                            contents.mapValues { (_, content) -> content.getOrThrow() })
                    }
                }
            }
        }

    private suspend fun runDatastoreBasedChecks(repoName: String): List<Deferred<Result<CheckResult>>> =
        coroutineScope {
            datastoreBasedChecks.map { check ->
                async { runCheck(repoName, check.javaClass.simpleName) { check.run(repoName) } }
            }

        }


    private suspend fun runGitHubAPIBasedChecks(repoName: String): List<Deferred<Result<CheckResult?>>> =
        coroutineScope {
            gitHubAPIBasedChecks.map { check ->
                async { runCheck(repoName, check.javaClass.simpleName) { check.run(repoName) } }
            }
        }

    private suspend fun <T> runCheck(repoName: String, checkName: String, run: suspend () -> T): Result<T> =
        try {
            Result.success(run())
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.logFailure(
                "Repository check failed; result omitted", ex,
                "repository" to repoName, "check" to checkName
            )
            Result.failure(ex)
        }
}