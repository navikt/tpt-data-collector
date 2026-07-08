package no.nav.service

import io.ktor.util.logging.KtorSimpleLogger
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
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

class DataCollectorService(
    val bigQueryClient: BigQueryClientInterface,
    val kafkaSender: KafkaSenderInterface,
    githubTokenProvider: GithubTokenProvider,
    val githubContentsClient: GithubRepositoryContentsClientInterface,
    val githubTreeClient: GithubGitTreeClientInterface,
) {
    var lastOkRun = Clock.System.now()
    val logger = KtorSimpleLogger(this::class.java.name)
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
        return lastOkRun > Clock.System.now().minus(26.hours)
    }
}
