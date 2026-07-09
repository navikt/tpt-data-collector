package no.nav.checks.repo

import kotlin.test.assertTrue
import no.nav.checks.AllGood
import no.nav.checks.NeedsWork
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UnpinnedActionVersionsCheckTest {

    @Test
    fun `This check should only care about workflow files`() {
        val allAvailableFiles = setOf(".github/workflows/yolo.yaml", ".github/workflows/yolo2.yml", "README.md", "Dockerfile")
        val check = UnpinnedActionVersionsCheck()
        val expected = listOf(".github/workflows/yolo.yaml", ".github/workflows/yolo2.yml")
        val actual = check.filesICareAbout(allAvailableFiles)
        assertEquals(expected, actual)
    }

    @Test
    fun `Unpinned versions are bad`() {
        val filesToCheck = mapOf(
            ".github/workflows/yolo.yaml" to """
                   steps:
                     - uses: actions/checkout@v7.0.0
                       with:
                         persist-credentials: false
                     - uses: actions/whatever@v3
            """.trimIndent()
        )
        val check = UnpinnedActionVersionsCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is NeedsWork)
        assertEquals(1, results.reasons.size)
    }

    @Test
    fun `Pinned versions are good`() {
        val filesToCheck = mapOf(
            ".github/workflows/yolo.yaml" to """
                   steps:
                     - uses: actions/checkout@9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0
            """.trimIndent()
        )
        val check = UnpinnedActionVersionsCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is AllGood)
    }

}