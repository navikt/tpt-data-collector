package no.nav.data

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

class DockerfileFeatures(
    dockerfileFeaturesList: List<Map<String, String>>,
    reposList: List<Map<String, String>>
) {
    val dockerfileFeatures = dockerfileFeaturesList.map { row ->
        val repoId = row["repo_id"] ?: throw RuntimeException("repo_id not found in dockerfile_features")
        val repoRow = reposList.findLast { it["repo_id"] == repoId }
        val repoName = if (repoRow != null) repoRow["full_name"] ?: "" else ""
        val whenCollected = convertToDateTime(row["when_collected"])
        val baseImageLine = (row["content"] ?: "").split("\n")
            .filter { !it.startsWith("#") }
            .filter { it.startsWith("FROM") }
            .lastOrNull()

        var baseImage = ""
        var pinsBaseImage = false
        var usesChainguard = false
        var usesDistoless = false
        if (baseImageLine != null) {
            baseImage = baseImageLine
                .replace("^FROM".toRegex(), "")
                .replace("@sha256:.*".toRegex(), "")
                .trim().split(" ").firstOrNull() ?: ""
            pinsBaseImage = baseImageLine.contains("@sha256:")
            val containsPullThough = baseImage.startsWith("europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/")
            val containsDirectChainguard = baseImage.startsWith("cgr.dev/chainguard/")
            usesChainguard = containsPullThough || containsDirectChainguard
            if (usesChainguard) {
                if (baseImage.endsWith("-dev"))
                    usesDistoless = false
                else {
                    val isNode = baseImage.contains("/node:")
                    val isNodeSlim = baseImage.contains("node:.*-slim$".toRegex())
                    if (isNode && !isNodeSlim)
                        usesDistoless = false
                    else
                        usesDistoless = true
                }
            } else {
                usesDistoless = baseImage.contains("/distroless/")
            }
        }
        DockerfileFeature(
            repoId = repoId,
            repoName = repoName,
            whenCollected = whenCollected,
            fileType = row["file_type"] ?: "",
            baseImage = baseImage,
            pinsBaseImage = pinsBaseImage,
            usesMultistage = row["uses_multistage"] == "true",
            usesChainguard = usesChainguard,
            usesDistroless = usesDistoless,
        )
    }

    private fun convertToDateTime(timeStampString: String?): LocalDateTime? {
        val timeStamp = timeStampString?.toDoubleOrNull() ?: return null
        return Instant.fromEpochMilliseconds((timeStamp*1000).toLong()).toLocalDateTime(TimeZone.UTC)
    }
}
