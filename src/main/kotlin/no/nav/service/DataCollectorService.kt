package no.nav.service

import no.nav.bigquery.BigQueryClientInterface
import no.nav.data.DockerfileFeatures
import no.nav.kafka.KafkaSenderInterface
import no.nav.zizmor.ZizmorResult
import no.nav.zizmor.ZizmorService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class DataCollectorService(
    val bigQueryClient: BigQueryClientInterface,
    val kafkaSender: KafkaSenderInterface,
    githubToken: String,
    zizmorCommand: String,
) {
    var lastOkRun = Clock.System.now()
    val logger: Logger = LoggerFactory.getLogger(this::class.java)
    val zizmorService = ZizmorService(githubToken, zizmorCommand)

    fun processDockerfileFeaturesAndSendToKafka(): Int {
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
        return mainTableList.size
    }

    fun checkRepoWithZizmorAndSendToKafka(repo: String): ZizmorResult {
        val saferRepo = repo.replace("/[^a-zA-ZÀ-Ÿ0-9-_.]/g".toRegex(), "")
        val resultString = zizmorService.runZizmorOnRepo("navikt", saferRepo)
        val result = zizmorService.analyseZizmorResult("navikt/$saferRepo", resultString)
        kafkaSender.sendToKafka("zizmor", result.toJson())
        return result
    }

    fun isAlive(): Boolean {
        return lastOkRun > Clock.System.now().minus(26.hours)
    }
}
