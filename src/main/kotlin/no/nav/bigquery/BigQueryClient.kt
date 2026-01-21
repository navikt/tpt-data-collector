package no.nav.bigquery

import com.google.cloud.bigquery.BigQueryOptions
import com.google.cloud.bigquery.DatasetId
import com.google.cloud.bigquery.JobId
import com.google.cloud.bigquery.JobInfo
import com.google.cloud.bigquery.QueryJobConfiguration
import com.google.cloud.bigquery.Table
import com.google.cloud.bigquery.TableDefinition
import com.google.cloud.bigquery.TableId
import com.google.cloud.bigquery.TableResult
import no.nav.logger
import kotlin.collections.orEmpty

class BigQueryClient(val projectId: String, val datasetId: DatasetId) : BigQueryClientInterface {
    private val bigQuery = BigQueryOptions.newBuilder()
        .setProjectId(projectId)
        .build()
        .service

    init {
        datasetPresent(datasetId)
    }

    private fun datasetPresent(datasetId: DatasetId) {
        requireNotNull(bigQuery.getDataset(datasetId)) {
            "Missing dataset: '${datasetId.dataset}'  in project: '${datasetId.project}' in BigQuery"
        }
    }

    private fun tablePresent(tableId: TableId) {
        requireNotNull(bigQuery.getTable(tableId)) {
            "Missing table: '${tableId.table}' in BigQuery"
        }
    }

    private fun getTable(tableId: TableId): Table =
        requireNotNull(bigQuery.getTable(tableId)) {
            "Missing table: '${tableId.table}' in BigQuery"
        }

    private fun queryTable(query: String): TableResult {
        val queryConfig = QueryJobConfiguration.newBuilder(query).setUseLegacySql(false).build()
        val jobId = JobId.newBuilder().setProject(projectId).build()

        var job = bigQuery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build())
        job = job.waitFor()

        if (job == null) {
            throw IllegalStateException("Job no longer exists for $projectId")
        } else if (job.status.executionErrors != null && job.status.executionErrors.isNotEmpty()) {
            throw IllegalStateException(
                "Job failed with unhandled error: \"${job.status.executionErrors[0].message}\" for $projectId"
            )
        }

        val result = job.getQueryResults()

        return result
    }

    override fun readTable(tableName: String): List<Map<String, String>> {
        val tableId = TableId.of(datasetId.project, datasetId.dataset, tableName)
        logger.info("BigQueryTable request received")
        tablePresent(tableId)
        val timestampColumn =
            if (tableName == "dockerfile_features")
                "when_collected"
            else if (tableName == "repos")
                "when_collected"
            else
                throw IllegalStateException("Table $tableName is not configured in code. Please fix here!!!")

        val fieldNames = getTable(tableId).getDefinition<TableDefinition>().schema?.fields?.map { it.name }.orEmpty()

        val result = queryTable(
            "SELECT array_agg(t ORDER BY when_collected DESC LIMIT 1)[ordinal(1)].* " +
                    "FROM `appsec.$tableName` t " +
                    "WHERE $timestampColumn > TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 30 DAY) " +
                    "GROUP BY t.repo_id;"
        )

        val dataList = mutableListOf<Map<String, String>>()
        result.iterateAll().forEach { row ->
            val rowMap = mutableMapOf<String, String>()
            row.forEachIndexed { idx, field ->
                rowMap[fieldNames[idx]] = field.value.toString()
            }
            dataList.add(rowMap)
        }
        return dataList
    }

}