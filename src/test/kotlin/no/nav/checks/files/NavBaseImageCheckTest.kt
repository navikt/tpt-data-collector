package no.nav.checks.files

import kotlin.test.assertTrue
import no.nav.checks.CheckResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NavBaseImageCheckTest {

    @Test
    fun `This check should only care about Dockerfiles`() {
        val allAvailableFiles = setOf("Dockerfile", "Dockerfile.test", "prod.dockerfile", "whatever")
        val check = NavBaseImageCheck()
        val expected = listOf("Dockerfile", "Dockerfile.test", "prod.dockerfile")
        val actual = check.filesICareAbout(allAvailableFiles)
        assertEquals(expected, actual)
    }

    @Test
    fun `Non Nav-images are OK if they are only used during build`() {
        val filesToCheck = mapOf(
            "Dockerfile" to """
               FROM yolo AS builder
               COPY . .
               FROM whatever/bogus
               RUN echo "hello"
            """.trimIndent()
        )
        val check = NavBaseImageCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is CheckResult.NeedsWork)
        assertEquals(1, results.reasons.size)
    }

    @Test
    fun `Non Nav-images are not OK if they are used @ runtime`() {
        val filesToCheck = mapOf(
            "Dockerfile" to """
               FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/thing:1.0 AS builder
               COPY . .
               FROM nginx:1.28
               RUN echo "hello"
            """.trimIndent()
        )
        val check = NavBaseImageCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is CheckResult.NeedsWork)
        println(results)
    }

}