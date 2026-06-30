package no.nav.service

import io.ktor.util.logging.KtorSimpleLogger
import java.io.IOException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import no.nav.bigquery.BigQueryClientInterface
import no.nav.data.DockerfileFeatureExtractor
import no.nav.data.DockerfileFeatures
import no.nav.data.isDockerfileCandidate
import no.nav.github.GithubGitTreeClientInterface
import no.nav.github.GithubRepositoryContentsClientInterface
import no.nav.github.GithubRequestException
import no.nav.github.GithubTokenProvider
import no.nav.github.logGithubFetchFailure
import no.nav.kafka.KafkaSenderInterface
import no.nav.data.CodeScanningToolStatus
import no.nav.github.GithubCodeScanningClientInterface
import no.nav.zizmor.ZizmorResult
import no.nav.zizmor.ZizmorService
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class DataCollectorService(
    val bigQueryClient: BigQueryClientInterface,
    val kafkaSender: KafkaSenderInterface,
    githubTokenProvider: GithubTokenProvider,
    zizmorCommand: String,
    val githubContentsClient: GithubRepositoryContentsClientInterface,
    val githubTreeClient: GithubGitTreeClientInterface,
    val githubCodeScanningClient: GithubCodeScanningClientInterface,
) {
    var lastOkRun = Clock.System.now()
    private var lastOkCodeScanningRun = Clock.System.now()
    val logger = KtorSimpleLogger(this::class.java.name)
    val zizmorService = ZizmorService(githubTokenProvider, zizmorCommand)
    private val dockerfileFeatureExtractor = DockerfileFeatureExtractor()

    fun processDockerfileFeaturesAndSendToKafka(): Int {
        logger.info("Scheduled update job started")
        val tableName = "dockerfile_features"
        logger.info("Starting to process $tableName")
        val mainTableList = bigQueryClient.readTable(tableName)
        logger.debug("$tableName query done")
        val reposList = bigQueryClient.readTable("repos")
        logger.info("Got ${mainTableList.size} $tableName and ${reposList.size} repos from queries")

        val dockerfileFeatures = DockerfileFeatures(mainTableList, reposList)
        val missingNames = dockerfileFeatures.dockerfileFeatures.filter { it.repoName.isEmpty() }.size
        if (missingNames > 0)
            logger.warn("$tableName: $missingNames number of repos (from \"repos\" table) are missing the name")

        logger.info("$tableName Sending to ${dockerfileFeatures.dockerfileFeatures.size} lines to kafka...")
        dockerfileFeatures.dockerfileFeatures.forEach{ kafkaSender.sendToKafka(tableName, it.toJson()) }
        logger.info("$tableName request processed and sent to kafka -> Done")
        lastOkRun = Clock.System.now()
        logger.info("Scheduled update job finished")
        return mainTableList.size
    }

    fun checkRepoWithZizmorAndSendToKafka(repo: String): ZizmorResult {
        val saferRepo = repo.replace("/[^a-zA-ZÀ-Ÿ0-9-_.]/g".toRegex(), "")
        val resultString = zizmorService.runZizmorOnRepo("navikt", saferRepo)
        val result = zizmorService.analyzeZizmorResult("navikt/$saferRepo", resultString)
        kafkaSender.sendToKafka("zizmor", result.toJson())
        return result
    }

    fun processChangedDockerfilesAndSendToKafka(
        repoId: String,
        repoFullName: String,
        ref: String,
        candidatePaths: Set<String>,
        removedPaths: Set<String> = emptySet(),
    ): Int {
        val owner = repoFullName.substringBefore("/")
        val repo = repoFullName.substringAfter("/")
        val pathsToProcess = if (removedPaths.isEmpty()) {
            candidatePaths
        } else {
            logger.info("Detected removed Dockerfile candidates in \"$repoFullName\": $removedPaths. Refreshing current Dockerfile candidates from GitHub.")
            githubTreeClient.listBlobPaths(owner, repo, ref).filter(::isDockerfileCandidate).toSet()
        }

        if (pathsToProcess.isEmpty()) {
            logger.info("Webhook Dockerfile processing finished for \"$repoFullName\": no Dockerfile candidates remain at $ref")
            return 0
        }

        val whenCollected = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        var processedCount = 0

        pathsToProcess.sorted().forEach { path ->
            try {
                val content = githubContentsClient.readFile(owner, repo, path, ref)
                val feature = dockerfileFeatureExtractor.extract(
                    repoId = repoId,
                    repoName = repoFullName,
                    path = path,
                    content = content,
                    whenCollected = whenCollected,
                )

                if (feature == null) {
                    logger.info("Skipping Dockerfile candidate \"$path\" in \"$repoFullName\": content did not validate as a Dockerfile")
                    return@forEach
                }

                kafkaSender.sendToKafka("dockerfile_features", feature.toJson())
                processedCount += 1
            } catch (e: GithubRequestException) {
                logger.logGithubFetchFailure(repoFullName, e)
            } catch (e: IOException) {
                logger.warn("Failed to fetch Dockerfile candidate \"$path\" from \"$repoFullName\": ${e.message}", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RuntimeException("Webhook Dockerfile processing interrupted for \"$repoFullName\"", e)
            }
        }

        logger.info("Webhook Dockerfile processing finished for \"$repoFullName\": published $processedCount message(s)")
        return processedCount
    }

    fun isAlive(): Boolean {
        val now = Clock.System.now()
        return lastOkRun > now.minus(26.hours) && lastOkCodeScanningRun > now.minus(26.hours)
    }

    fun processCodeScanningToolsAndSendToKafka(): Int {
        logger.info("Code scanning job started")
        val reposList = bigQueryClient.readTable("repos")
        if (reposList.isEmpty()) {
            logger.warn("Code scanning job: repos table returned 0 rows, skipping")
            return 0
        }
        logger.info("Code scanning job: processing ${reposList.size} repos")
        val collectedAt = Clock.System.now().toString()
        var processedCount = 0

        reposList.forEach { row ->
            val repoId = row["repo_id"] ?: run {
                logger.warn("Code scanning job: skipping row without repo_id")
                return@forEach
            }
            val fullName = row["full_name"] ?: run {
                logger.warn("Code scanning job: skipping repo without full_name (repo_id=$repoId)")
                return@forEach
            }
            val repoName = fullName.substringAfter("/", missingDelimiterValue = "").takeIf { it.isNotEmpty() } ?: run {
                logger.warn("Code scanning job: skipping repo with malformed full_name \"$fullName\" (repo_id=$repoId)")
                return@forEach
            }
            try {
                val analyses = githubCodeScanningClient.getLatestAnalyses("navikt", repoName)
                if (analyses.isEmpty()) {
                    logger.debug("Code scanning job: no analyses for \"$fullName\", skipping")
                    return@forEach
                }
                val status = CodeScanningToolStatus.from(repoId, fullName, collectedAt, analyses)
                kafkaSender.sendToKafka("code_scanning_tools", status.toJson())
                processedCount++
            } catch (e: GithubRequestException) {
                logger.warn("Code scanning job: GitHub request failed for \"$fullName\": ${e.message}")
            } catch (e: Exception) {
                logger.warn("Code scanning job: unexpected error for \"$fullName\"", e)
            }
        }

        logger.info("Code scanning job finished: $processedCount/${reposList.size} repos published")
        lastOkCodeScanningRun = Clock.System.now()
        return processedCount
    }
}
