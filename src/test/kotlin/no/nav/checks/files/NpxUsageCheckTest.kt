package no.nav.checks.files

import kotlin.test.assertTrue
import no.nav.checks.CheckResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NpxUsageCheckTest {

    @Test
    fun `This check should only care about package json files`() {
        val allAvailableFiles = setOf("package.json", "./subfolder/package.json", "README.md", "Dockerfile")
        val check = NpxUsageCheck()
        val expected = listOf("package.json", "./subfolder/package.json")
        val actual = check.filesICareAbout(allAvailableFiles)
        assertEquals(expected, actual)
    }

    @Test
    fun `npx usage is bad`() {
        val filesToCheck = mapOf(
            "package.json" to """
                   {
                      "scripts": {
                        "yolo": "npx thething",
                        "test": "jest",
                        "build": "tsc && vite build"
                      }
                }
            """.trimIndent()
        )
        val check = NpxUsageCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is CheckResult.NeedsWork)
        assertEquals(1, results.reasons.size)
    }

    @Test
    fun `no npx usage is good`() {
        val filesToCheck = mapOf(
            "package.json" to """
                   {
                     "scripts": {
                       "start": "node server.js",
                       "test": "jest",
                       "build": "tsc"
                     }
                   }
            """.trimIndent()
        )
        val check = NpxUsageCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is CheckResult.AllGood)
    }

}