package no.nav.data

import kotlinx.serialization.json.Json

class DockerfileFeatures(
    dockerfileFeaturesList: List<Map<String, String>>,
    reposList: List<Map<String, String>>
) {
    val dockerfileFeatures = dockerfileFeaturesList.map { row ->
        val repoId = row["repo_id"] ?: throw RuntimeException("repo_id not found in dockerfile_features")
        val repoRow = reposList.findLast { it["repo_id"] == repoId }
        val repoName = if (repoRow != null) repoRow["full_name"] ?: "" else ""
        val dockerfileContent = row["content"] ?: ""
        val usesChainguard = dockerfileContent.contains("chainguard")
        val usesDistoless = usesChainguard || dockerfileContent.contains("distroless")
        DockerfileFeature(
            repoId = repoId,
            repoName = repoName,
            fileType = row["file_type"] ?: "",
            usesMultistage = row["uses_multistage"] == "true",
            usesChainguard = usesChainguard,
            usesDistroless = usesDistoless,
        )
    }

    override fun toString(): String {
        return Json.encodeToString(dockerfileFeatures)
    }
}
