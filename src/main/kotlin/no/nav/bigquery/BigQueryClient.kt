package no.nav.bigquery

import com.google.cloud.bigquery.BigQueryOptions
import com.google.cloud.bigquery.DatasetId
import com.google.cloud.bigquery.JobId
import com.google.cloud.bigquery.JobInfo
import com.google.cloud.bigquery.QueryJobConfiguration
import com.google.cloud.bigquery.Table
import com.google.cloud.bigquery.TableId
import com.google.cloud.bigquery.TableResult
import org.slf4j.LoggerFactory

class BigQueryClient (val projectId: String) {
    private val bigQuery = BigQueryOptions.newBuilder()
        .setProjectId(projectId)
        .build()
        .service

    companion object {
        private val log = LoggerFactory.getLogger(BigQueryClient::class.java)
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

    fun getTable(tableId: TableId): Table =
        requireNotNull(bigQuery.getTable(tableId)) {
            "Mangler tabell: '${tableId.table}' i BigQuery"
        }

    fun queryTable(query: String): TableResult {
        val queryConfig = QueryJobConfiguration.newBuilder(query).setUseLegacySql(false).build()
        val jobId = JobId.newBuilder().setProject(projectId).build()

        var job = bigQuery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build())
        job = job.waitFor()

        if (job == null) {
            throw IllegalStateException("Job no longer exists for $projectId")
        } else if (job.status.executionErrors != null && job.status.executionErrors.isNotEmpty()) {
            throw IllegalStateException(
                "Job failed with unhandled error: \"${job.status.executionErrors[0].message}\" for $projectId")
        }

        val result = job.getQueryResults()

        return result
    }

}