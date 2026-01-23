package no.nav.service

import no.nav.bigquery.BigQueryClientInterface
import no.nav.data.DockerfileFeatures
import no.nav.kafka.KafkaSenderInterface
import no.nav.logger

class DataCollectorService(
    val bigQueryClient: BigQueryClientInterface,
    val kafkaSender: KafkaSenderInterface
) {
    fun processDockerfileFeaturesAndSendToKafka(): Int {
        val tableName = "dockerfile_features"
        logger.debug("Starting to handle $tableName request")
        val mainTableList = bigQueryClient.readTable(tableName)
        logger.debug("$tableName query done")
        val reposList = bigQueryClient.readTable("repos")
        logger.debug("Got ${mainTableList.size} $tableName and ${reposList.size} repos")

        val dockerfileFeatures = DockerfileFeatures(mainTableList, reposList)
        val missingNames = dockerfileFeatures.dockerfileFeatures.filter { it.repoName.isEmpty() }.size
        if (missingNames > 0)
            logger.warn("$tableName: $missingNames number of repos (from \"repos\" table) are missing the name")

        logger.debug("$tableName Sending to kafka...")
        kafkaSender.sendToKafka(tableName, dockerfileFeatures.toString())
        logger.debug("$tableName Done")
        return mainTableList.size
    }
}
