package no.nav.bigquery

import com.google.cloud.bigquery.BigQueryOptions
import com.google.cloud.bigquery.DatasetId
import com.google.cloud.bigquery.Table
import com.google.cloud.bigquery.TableId
import org.slf4j.LoggerFactory

class BigQueryClient (projectId: String) {
    private val bigQuery = BigQueryOptions.newBuilder()
        .setProjectId(projectId)
        .build()
        .service

    companion object {
        private val log = LoggerFactory.getLogger(BigQueryClient::class.java)
    }

    private fun getTable(tableId: TableId): Table =
        requireNotNull(bigQuery.getTable(tableId)) {
            "Mangler tabell: '${tableId.table}' i BigQuery"
        }

    fun datasetPresent(datasetId: DatasetId): Boolean {
        val present = bigQuery.getDataset(datasetId) != null
        log.info("dataset: $datasetId, present: $present")
        return present
    }

    fun tablePresent(tableId: TableId): Boolean {
        val present = bigQuery.getTable(tableId) != null
        log.info("table: $tableId, present: $present")
        return present
    }
}