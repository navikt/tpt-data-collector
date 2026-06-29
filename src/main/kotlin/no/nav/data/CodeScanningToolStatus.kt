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
        /**
         * 🔴 Rød sone — implementer denne funksjonen selv.
         *
         * Logikken skal:
         * - Gruppere [analyses] per verktøynavn (tool.name)
         * - Plukke den nyeste analysen per verktøy (høyest createdAt)
         * - Sette status = "ok" hvis error == "", ellers "error"
         * - Returnere én [ToolStatus] per unikt verktøy som har kjørt
         * - Returnere tom liste hvis [analyses] er tom (= ingen code scanning konfigurert)
         */
        fun from(
            repoId: String,
            repoName: String,
            collectedAt: String,
            analyses: List<CodeScanningAnalysis>,
        ): CodeScanningToolStatus {
            TODO("🔴 Rød sone: implementer CodeScanningToolStatus.from() selv — se kommentar over")
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
