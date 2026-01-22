package no.nav

import com.google.cloud.bigquery.DatasetId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.bigquery.BigQueryClient
import no.nav.bigquery.BigQueryClientInterface
import no.nav.bigquery.DummyBigQuery
import no.nav.data.DockerfileFeatures
import no.nav.kafka.DummyKafkaSender
import no.nav.kafka.KafkaSender
import no.nav.config.ApplikasjonsConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory

fun main(args: Array<String>): Unit = EngineMain.main(args)

val logger: Logger = LoggerFactory.getLogger("Main")

fun Application.module(testing: Boolean = false) {
val config = ApplikasjonsConfig()
    val datasetId = DatasetId.of(config.projectId, config.datasetName)

    val bigQueryClient: BigQueryClientInterface = if (testing)
        DummyBigQuery()
    else
        BigQueryClient(config.projectId, datasetId)

    val kafkaSender = if (testing)
        DummyKafkaSender()
    else
        KafkaSender()

    routing {
        get("/") {
            logger.info("Request received")
            call.respond(HttpStatusCode.OK, "Hello, World!")
        }
        get("/bigquery/dockerfile_features") {
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
            call.respond(
                HttpStatusCode.OK, "BigQuery: Number of lines sent: ${mainTableList.size}\n"
            )
        }
    }
}