@file:OptIn(ExperimentalSerializationApi::class)

package no.nav.github

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames

class GithubCodeScanningClient(
    private val apiClient: GithubApiClient,
) : GithubCodeScanningClientInterface {
    private val json = Json { ignoreUnknownKeys = true }

    override fun getLatestAnalyses(owner: String, repo: String): List<CodeScanningAnalysis> {
        val response = apiClient.get(
            operation = OPERATION,
            owner = owner,
            repo = repo,
            path = "code-scanning/analyses",
            endpoint = "/repos/$owner/${encodePathSegment(repo)}/code-scanning/analyses?per_page=10",
            accept = "application/vnd.github+json",
            allow404 = true,
        )
        if (response.statusCode == 404) return emptyList()
        return json.decodeFromString(response.body)
    }

    private companion object {
        private const val OPERATION = "rest.code-scanning.analyses.list"
    }
}

@Serializable
data class CodeScanningAnalysis(
    val tool: AnalysisTool,
    @JsonNames("created_at") val createdAt: String,
    @JsonNames("results_count") val resultsCount: Int,
    val error: String,
)

@Serializable
data class AnalysisTool(
    val name: String,
    val version: String? = null,
)
