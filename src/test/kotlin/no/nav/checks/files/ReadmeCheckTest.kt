package no.nav.checks.files

import kotlin.test.assertTrue
import no.nav.checks.CheckResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadmeCheckTest {

    @Test
    fun `This check should only care about readme files`() {
        val allAvailableFiles = setOf("README.md", "Readme.md", "readme.md",
            "README.txt", "Readme.txt", "readme.txt",
            "README", "Readme", "readme",
            "whatever", "./subdir/yolo", "IRRELEVANT")
        val check = ReadmeCheck()
        val expected = listOf("README.md", "Readme.md", "readme.md",
            "README.txt", "Readme.txt", "readme.txt",
            "README", "Readme", "readme")
        val actual = check.filesICareAbout(allAvailableFiles)
        assertEquals(expected, actual)
    }

    @Test
    fun `Repo should contain at least one Readme file`() {
        val check = ReadmeCheck()
        val result = check.run("tullerepo", emptyMap())
        assertTrue(result is CheckResult.NeedsWork)
    }

    @Test
    fun `Readme cannot be empty`() {
        val filesToCheck = mapOf("readme" to "")
        val check = ReadmeCheck()
        val result = check.run("tullerepo", filesToCheck)
        assertTrue(result is CheckResult.NeedsWork)
    }

    @Test
    fun `Space filling is not allowed`() {
        val filesToCheck = mapOf("readme" to """
        """.trimIndent())
        val check = ReadmeCheck()
        val result = check.run("tullerepo", filesToCheck)
        assertTrue(result is CheckResult.NeedsWork)
    }

    @Test
    fun `Readme must contain more than one line`() {
        val filesToCheck = mapOf("readme" to "this is the only line")
        val check = ReadmeCheck()
        val result = check.run("tullerepo", filesToCheck)
        assertTrue(result is CheckResult.NeedsWork)
    }

    @Test
    fun `Good ones shall pass`() {
        val filesToCheck = mapOf("readme" to """
            line one
            line two
        """.trimIndent())
        val check = ReadmeCheck()
        val result = check.run("tullerepo", filesToCheck)
        assertTrue(result is CheckResult.AllGood)
    }

}