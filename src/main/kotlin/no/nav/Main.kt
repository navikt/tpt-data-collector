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
import no.nav.util.getEnvVar
import org.slf4j.Logger
import org.slf4j.LoggerFactory

fun main(args: Array<String>): Unit = EngineMain.main(args)

val logger: Logger = LoggerFactory.getLogger("Main")

fun Application.module(testing: Boolean = false) {
    val projectId = getEnvVar("GCP_TEAM_PROJECT_ID", "appsec")
    val datasetId = DatasetId.of(projectId, getEnvVar("BIGQUERY_DATASET_ID", "appsec"))
    val bigQueryClient: BigQueryClientInterface = if (testing)
        DummyBigQuery()
    else
        BigQueryClient(projectId, datasetId)

    routing {
        get("/") {
            logger.info("Request received")
            call.respond(HttpStatusCode.OK, "Hello, World!")
        }
        get("/bigquery/dockerfile_features") {
            logger.info("Starting handling of request for dockerfile_features")
            val dockerfileFeaturesList = bigQueryClient.readTable("dockerfile_features")
            val reposList = bigQueryClient.readTable("repos")

            val dockerfileFeatures = DockerfileFeatures(dockerfileFeaturesList, reposList)
            call.respond(
                HttpStatusCode.OK, "BigQuery: \n" +
                        "result: $dockerfileFeatures\n"
            )
        }
    }
}