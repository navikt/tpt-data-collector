package no.nav.data

import no.nav.github.AnalysisTool
import no.nav.github.CodeScanningAnalysis
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 🔴 Rød sone — implementer testene og [CodeScanningToolStatus.from] selv.
 *
 * Når du er ferdig, svar gjerne på:
 * «Hva gjør CodeScanningToolStatus.from(), og hvorfor valgte du den tilnærmingen?»
 */
class CodeScanningToolStatusTest {
    private val repoId = "123456"
    private val repoName = "navikt/demo"
    private val collectedAt = "2026-06-29T02:00:00Z"

    @Test
    fun `TODO - repo uten analyser får tom tools-liste`() {
        // TODO: kall CodeScanningToolStatus.from() med tom liste og verifiser tools == emptyList()
    }

    @Test
    fun `TODO - verktøy med tomt error-felt får status ok`() {
        // TODO: lag en CodeScanningAnalysis med error = "" og verifiser status == "ok"
    }

    @Test
    fun `TODO - verktøy med ikke-tomt error-felt får status error`() {
        // TODO: lag en CodeScanningAnalysis med error = "exit status 1" og verifiser status == "error"
    }

    @Test
    fun `TODO - error-feltet bevares i ToolStatus ved feil`() {
        // TODO: verifiser at feilmeldingen fra GitHub vises i ToolStatus.error
    }

    @Test
    fun `TODO - kun nyeste analyse per verktøy brukes`() {
        // TODO: lag to analyser for samme verktøy med ulik createdAt,
        //       verifiser at kun den nyeste brukes
    }

    @Test
    fun `TODO - flere ulike verktøy gir én ToolStatus per verktøy`() {
        // TODO: lag analyser for CodeQL og Trivy, verifiser at begge er representert
    }

    @Test
    fun `TODO - repoId og repoName bevares i resultatet`() {
        // TODO: verifiser at repoId og repoName fra input vises i CodeScanningToolStatus
    }

    @Test
    fun `TODO - collectedAt bevares i resultatet`() {
        // TODO: verifiser at collectedAt fra input vises i CodeScanningToolStatus
    }

    // Hjelpemetode — bruk gjerne denne i testene over
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
