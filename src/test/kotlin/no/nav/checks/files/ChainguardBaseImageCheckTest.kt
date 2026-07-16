package no.nav.checks.files

import kotlin.test.assertTrue
import no.nav.checks.CheckResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChainguardBaseImageCheckTest {

    @Test
    fun `This check should only care about Dockerfiles`() {
        val allAvailableFiles = setOf("Dockerfile", "Dockerfile.test", "prod.dockerfile", "whatever")
        val check = ChainguardBaseImageCheck()
        val expected = listOf("Dockerfile", "Dockerfile.test", "prod.dockerfile")
        val actual = check.filesICareAbout(allAvailableFiles)
        assertEquals(expected, actual)
    }

    @Test
    fun `Non-Chainguard images should be flagged`() {
        val filesToCheck = mapOf(
            "Dockerfile" to """
               FROM yolo AS builder
               COPY . .
               FROM whatever/bogus
               RUN echo "hello"
            """.trimIndent()
        )
        val check = ChainguardBaseImageCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is CheckResult.NeedsWork)
        assertEquals(2, results.reasons.size)
    }

    @Test
    fun `Chainguard images are good`() {
        val filesToCheck = mapOf(
            "Dockerfile" to """
               FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/thing:1.0 AS builder
               COPY . .
               FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/otherthing:1.0
               RUN echo "hello"
            """.trimIndent()
        )
        val check = ChainguardBaseImageCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is CheckResult.AllGood)
    }

}