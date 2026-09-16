package no.nav.checks.files

import kotlin.test.assertTrue
import no.nav.checks.CheckResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HasGitignoreCheckTest {

    @Test
    fun `This check should only care about gitignore files`() {
        val allAvailableFiles = setOf(".gitignore", "README.md", "Dockerfile")
        val check = HasGitignoreCheck()
        val expected = listOf(".gitignore")
        val actual = check.filesICareAbout(allAvailableFiles)
        assertEquals(expected, actual)
    }

    @Test
    fun `no gitignore present is bad`() {
        val filesToCheck = mapOf(
            ".github/workflows/yolo.yaml" to "whatever"
        )
        val check = HasGitignoreCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is CheckResult.NeedsWork)
        assertEquals(1, results.reasons.size)
    }

    @Test
    fun `gitignore present is good`() {
        val filesToCheck = mapOf(
            ".gitignore" to "whatever"
        )
        val check = HasGitignoreCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is CheckResult.AllGood)
    }

}