package no.nav.checks.files

import kotlin.test.assertTrue
import no.nav.checks.CheckResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PwnRequestCheckTest {

    @Test
    fun `This check should only care about workflow files`() {
        val allAvailableFiles = setOf(".github/workflows/yolo.yaml", ".github/workflows/yolo2.yml", "README.md", "Dockerfile")
        val check = PwnRequestCheck()
        val expected = listOf(".github/workflows/yolo.yaml", ".github/workflows/yolo2.yml")
        val actual = check.filesICareAbout(allAvailableFiles)
        assertEquals(expected, actual)
    }

    @Test
    fun `pull_request_target is bad`() {
        val filesToCheck = mapOf(
            ".github/workflows/yolo.yaml" to """
                   on:
                      pull_request_target:
                        types: [assigned, opened, synchronize, reopened]
            """.trimIndent()
        )
        val check = PwnRequestCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is CheckResult.NeedsWork)
        assertEquals(1, results.reasons.size)
    }

    @Test
    fun `Other triggers are fine`() {
        val filesToCheck = mapOf(
            ".github/workflows/yolo.yaml" to """
                   on:
                      pull_request:
                        types: [assigned, opened, synchronize, reopened]
            """.trimIndent()
        )
        val check = PwnRequestCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is CheckResult.AllGood)
    }

}