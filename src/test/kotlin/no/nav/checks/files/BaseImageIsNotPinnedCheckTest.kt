package no.nav.checks.files

import kotlin.test.assertTrue
import no.nav.checks.CheckResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BaseImageIsNotPinnedCheckTest {

    @Test
    fun `This check should only care about Dockerfiles`() {
        val allAvailableFiles = setOf("Dockerfile", "Dockerfile.test", "prod.dockerfile", "whatever")
        val check = BaseImageIsNotPinnedCheck()
        val expected = listOf("Dockerfile", "Dockerfile.test", "prod.dockerfile")
        val actual = check.filesICareAbout(allAvailableFiles)
        assertEquals(expected, actual)
    }

    @Test
    fun `Chainguard images doesn't have to be pinned`() {
        val filesToCheck = mapOf(
            "Dockerfile" to """
               FROM cgr.dev/chainguard/go as builder
               COPY /from /to
               FROM cgr.dev/chainguard/go:latest
               RUN echo "hello"
            """.trimIndent()
        )
        val check = BaseImageIsNotPinnedCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is CheckResult.AllGood)
    }

    @Test
    fun `Non-Chainguard images with tags should be flagged`() {
        val filesToCheck = mapOf(
            "Dockerfile" to """
               FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/thing:1.0 AS builder
               COPY . .
               FROM nginx:1.28
               RUN echo "hello"
            """.trimIndent()
        )
        val check = BaseImageIsNotPinnedCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is CheckResult.NeedsWork)
    }

    @Test
    fun `Non-Chainguard images with implicit latest tags should be flagged`() {
        val filesToCheck = mapOf(
            "Dockerfile" to """
               FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/thing:1.0 AS builder
               COPY . .
               FROM nginx as yolo
               RUN echo "hello"
            """.trimIndent()
        )
        val check = BaseImageIsNotPinnedCheck()
        val results = check.run("bogusrepo", filesToCheck)
        assertTrue(results is CheckResult.NeedsWork)
        println(results)
    }

}