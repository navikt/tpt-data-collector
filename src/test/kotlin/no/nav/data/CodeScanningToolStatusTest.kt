package no.nav.data

import no.nav.github.AnalysisTool
import no.nav.github.CodeScanningAnalysis
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeScanningToolStatusTest {
    private val repoId = "123456"
    private val repoName = "navikt/demo"
    private val collectedAt = "2026-06-29T02:00:00Z"

    @Test
    fun `repo uten analyser får tom tools-liste`() {
        val result = CodeScanningToolStatus.from(repoId, repoName, collectedAt, emptyList())

        assertTrue(result.tools.isEmpty())
    }

    @Test
    fun `verktøy med tomt error-felt får status ok`() {
        val result = CodeScanningToolStatus.from(repoId, repoName, collectedAt, listOf(analysis("CodeQL", error = "")))

        assertEquals("ok", result.tools.single().status)
    }

    @Test
    fun `verktøy med ikke-tomt error-felt får status error`() {
        val result = CodeScanningToolStatus.from(repoId, repoName, collectedAt, listOf(analysis("CodeQL", error = "exit status 1")))

        assertEquals("error", result.tools.single().status)
    }

    @Test
    fun `error-feltet bevares i ToolStatus ved feil`() {
        val result = CodeScanningToolStatus.from(repoId, repoName, collectedAt, listOf(analysis("CodeQL", error = "exit status 1")))

        assertEquals("exit status 1", result.tools.single().error)
    }

    @Test
    fun `error-feltet er null når verktøyet er ok`() {
        val result = CodeScanningToolStatus.from(repoId, repoName, collectedAt, listOf(analysis("CodeQL", error = "")))

        assertNull(result.tools.single().error)
    }

    @Test
    fun `kun nyeste analyse per verktøy brukes`() {
        val analyses = listOf(
            analysis("CodeQL", createdAt = "2026-06-27T04:00:00Z"),
            analysis("CodeQL", createdAt = "2026-06-28T04:00:00Z"),
        )

        val result = CodeScanningToolStatus.from(repoId, repoName, collectedAt, analyses)

        assertEquals(1, result.tools.size)
        assertEquals("2026-06-28T04:00:00Z", result.tools.single().lastAnalysisAt)
    }

    @Test
    fun `flere ulike verktøy gir én ToolStatus per verktøy`() {
        val analyses = listOf(
            analysis("CodeQL"),
            analysis("Trivy"),
        )

        val result = CodeScanningToolStatus.from(repoId, repoName, collectedAt, analyses)

        assertEquals(2, result.tools.size)
        assertTrue(result.tools.any { it.name == "CodeQL" })
        assertTrue(result.tools.any { it.name == "Trivy" })
    }

    @Test
    fun `repoId og repoName bevares i resultatet`() {
        val result = CodeScanningToolStatus.from(repoId, repoName, collectedAt, emptyList())

        assertEquals(repoId, result.repoId)
        assertEquals(repoName, result.repoName)
    }

    @Test
    fun `collectedAt bevares i resultatet`() {
        val result = CodeScanningToolStatus.from(repoId, repoName, collectedAt, emptyList())

        assertEquals(collectedAt, result.collectedAt)
    }

    private fun analysis(
        toolName: String,
        createdAt: String = "2026-06-28T04:00:00Z",
        resultsCount: Int = 0,
        error: String = "",
    ) = CodeScanningAnalysis(
        tool = AnalysisTool(name = toolName),
        createdAt = createdAt,
        resultsCount = resultsCount,
        error = error,
    )
}
