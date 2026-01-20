package no.nav

import com.google.cloud.bigquery.DatasetId
import com.google.cloud.bigquery.StandardTableDefinition
import com.google.cloud.bigquery.TableId
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
            val present = bigQueryClient.datasetPresent(datasetId = datasetId)
            call.respond(HttpStatusCode.OK, "BigQuery ${datasetId.dataset}: $present\n")
        }
        get("/bigquery/table/{tableName}") {
            val tableName = call.parameters["tableName"]
            val tableId = TableId.of(datasetId.project, datasetId.dataset, tableName)
            logger.info("BigQueryTable request received")
            val present = bigQueryClient.tablePresent(tableId)
            if (!present) {
                call.respond(HttpStatusCode.NotFound, "Table ${tableId.dataset} not found\n")
            }
            val fieldNames = bigQueryClient.getTable(tableId).getDefinition<StandardTableDefinition>().schema?.fields?.map{it.name}.orEmpty()
            call.respond(HttpStatusCode.OK, "BigQuery ${tableId.table}: ${fieldNames.joinToString { ", " }}\n")
        }
    }
}