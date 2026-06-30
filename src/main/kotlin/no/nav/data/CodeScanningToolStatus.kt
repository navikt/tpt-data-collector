package no.nav.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.github.CodeScanningAnalysis

@Serializable
data class CodeScanningToolStatus(
    val repoId: String,
    val repoName: String,
    val collectedAt: String,
    val tools: List<ToolStatus>,
) {
    fun toJson(): String = Json.encodeToString(this)

    companion object {
        fun from(
            repoId: String,
            repoName: String,
            collectedAt: String,
            analyses: List<CodeScanningAnalysis>,
        ): CodeScanningToolStatus {
            val tools = analyses
                .groupBy { it.tool.name }
                .map { (_, analysesForTool) ->
                    val latest = analysesForTool.maxBy { it.createdAt }
                    ToolStatus(
                        name = latest.tool.name,
                        status = if (latest.error.isEmpty()) "ok" else "error",
                        lastAnalysisAt = latest.createdAt,
                        resultCount = latest.resultsCount,
                        error = latest.error.ifEmpty { null },
                    )
                }
            return CodeScanningToolStatus(
                repoId = repoId,
                repoName = repoName,
                collectedAt = collectedAt,
                tools = tools,
            )
        }
    }
}

@Serializable
data class ToolStatus(
    val name: String,
    val status: String,
    val lastAnalysisAt: String,
    val resultCount: Int,
    val error: String?,
)
