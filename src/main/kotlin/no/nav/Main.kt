package no.nav

import com.google.cloud.bigquery.DatasetId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.bigquery.BigQueryClient
import no.nav.util.getEnvVar
import org.slf4j.Logger
import org.slf4j.LoggerFactory

fun main(args: Array<String>): Unit = EngineMain.main(args)

val logger: Logger = LoggerFactory.getLogger("Main")

fun Application.module() {
    val projectId = getEnvVar("GCP_TEAM_PROJECT_ID", "appsec")
    val bigQueryClient = BigQueryClient(projectId)
    val datasetId = DatasetId.of(projectId, getEnvVar("BIGQUERY_DATASET_ID", "appsec"), )
    routing {
        get("/") {
            logger.info("Request received")
            call.respond(HttpStatusCode.OK, "Hello, World!")
        }
        get("/bigquery") {
            logger.info("BigQuery request received")
            bigQueryClient.datasetPresent(datasetId = datasetId)
            call.respond(HttpStatusCode.OK, "BigQuery")
        }
    }
}