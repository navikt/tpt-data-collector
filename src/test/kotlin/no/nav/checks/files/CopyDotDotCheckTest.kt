package no.nav.checks.files

import kotlin.test.assertTrue
import no.nav.checks.AllGood
import no.nav.checks.NeedsWork
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CopyDotDotCheckTest {

    @Test
    fun `This check should only care about Dockerfiles`() {
        val allAvailableFiles = setOf("Dockerfile", "Dockerfile.test", "prod.dockerfile", "whatever")
        val check = ChainguardBaseImageCheck()
        val expected = listOf("Dockerfile", "Dockerfile.test", "prod.dockerfile")
        val actual = check.filesICareAbout(allAvailableFiles)
        assertEquals(expected, actual)
    }

    @Test
    fun `Copy dot dot should be flagged`() {
        val filesToCheck = mapOf(
            "Dockerfile" to """
               FROM yolo AS builder
               COPY . .
            """.trimIndent()
        )
        val check = CopyDotDotCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is NeedsWork)
        assertEquals(1, results.reasons.size)
    }

    @Test
    fun `No Copy dot dots is good`() {
        val filesToCheck = mapOf(
            "Dockerfile" to """
               FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/thing:1.0 AS builder
               COPY a b
            """.trimIndent()
        )
        val check = CopyDotDotCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is AllGood)
    }

}