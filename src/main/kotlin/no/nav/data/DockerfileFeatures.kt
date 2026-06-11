package no.nav.data

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

class DockerfileFeatures(
    dockerfileFeaturesList: List<Map<String, String>>,
    reposList: List<Map<String, String>>
) {
    private val extractor = DockerfileFeatureExtractor()
    val dockerfileFeatures = dockerfileFeaturesList.map { row ->
        val repoId = row["repo_id"] ?: throw RuntimeException("repo_id not found in dockerfile_features")
        val repoRow = reposList.findLast { it["repo_id"] == repoId }
        val repoName = if (repoRow != null) repoRow["full_name"] ?: "" else ""
        val whenCollected = convertToDateTime(row["when_collected"])
        extractor.extract(
            repoId = repoId,
            repoName = repoName,
            path = row["path"] ?: "",
            whenCollected = whenCollected,
            fileType = row["file_type"] ?: "",
            content = row["content"] ?: "",
        ) ?: throw RuntimeException("Could not extract Dockerfile feature from BigQuery row for repo_id=$repoId")
    }

    private fun convertToDateTime(timeStampString: String?): LocalDateTime? {
        val timeStamp = timeStampString?.toDoubleOrNull() ?: return null
        return Instant.fromEpochMilliseconds((timeStamp*1000).toLong()).toLocalDateTime(TimeZone.UTC)
    }
}
