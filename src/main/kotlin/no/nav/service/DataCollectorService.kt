package no.nav.service

import no.nav.bigquery.BigQueryClientInterface
import no.nav.data.DockerfileFeatures
import no.nav.kafka.KafkaSenderInterface
import no.nav.logger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class DataCollectorService(
    val bigQueryClient: BigQueryClientInterface,
    val kafkaSender: KafkaSenderInterface
) {
    var lastOkRun = Clock.System.now()

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

    fun isAlive(): Boolean {
        return lastOkRun > Clock.System.now().minus(26.hours)
    }
}
