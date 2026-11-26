package no.nav.checks.files

import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import no.nav.checks.CheckResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class DistrolessCheckTest {

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `An approved Dockerfile does not hide a non-recommended runtime image`(reverseOrder: Boolean) {
        val files = mapOf(
            "Dockerfile" to "FROM nginx:1.28",
            "Dockerfile.prod" to "FROM cgr.dev/chainguard/go:latest"
        )
        val filesToCheck = if (reverseOrder) files.toList().reversed().toMap() else files

        val result = assertIs<CheckResult.NeedsWork>(DistrolessCheck().run("navikt/bogusrepo", filesToCheck))

        assertEquals(
            listOf("'nginx:1.28' is not a recommended base image. consider switching to distroless"),
            result.reasons
        )
    }

    @Test
    fun `Each Dockerfile contributes its non-recommended runtime image but not its build image`() {
        val filesToCheck = mapOf(
            "Dockerfile" to "FROM build-only AS builder\nFROM nginx:1.28",
            "Dockerfile.prod" to "FROM another-build-only AS builder\nFROM ubuntu:24.04"
        )

        val result = assertIs<CheckResult.NeedsWork>(DistrolessCheck().run("navikt/bogusrepo", filesToCheck))

        assertEquals(
            setOf(
                "'nginx:1.28' is not a recommended base image. consider switching to distroless",
                "'ubuntu:24.04' is not a recommended base image. consider switching to distroless"
            ),
            result.reasons.toSet()
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "# No FROM instruction", "FROM { templated }"])
    fun `A Dockerfile without a verifiable image does not hide another runtime image`(contents: String) {
        val filesToCheck = mapOf(
            "Dockerfile" to "FROM nginx:1.28",
            "Dockerfile.prod" to contents
        )

        val result = assertIs<CheckResult.NeedsWork>(DistrolessCheck().run("navikt/bogusrepo", filesToCheck))

        assertEquals(
            listOf("'nginx:1.28' is not a recommended base image. consider switching to distroless"),
            result.reasons
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "# No FROM instruction", "FROM { templated }"])
    fun `Existing Dockerfiles without any verifiable image remain unavailable`(contents: String) {
        assertFailsWith<NoSuchElementException> {
            DistrolessCheck().run("navikt/bogusrepo", mapOf("Dockerfile" to contents))
        }
    }

    @Test
    fun `No Dockerfiles means no non-recommended images`() {
        val result = DistrolessCheck().run("navikt/bogusrepo", emptyMap())

        assertTrue(result is CheckResult.AllGood)
    }

    @Test
    fun `This check should only care about Dockerfiles`() {
        val allAvailableFiles = setOf("Dockerfile", "Dockerfile.test", "prod.dockerfile", "whatever")
        val check = DistrolessCheck()
        val expected = listOf("Dockerfile", "Dockerfile.test", "prod.dockerfile")
        val actual = check.filesICareAbout(allAvailableFiles)
        assertEquals(expected, actual)
    }

    @Test
    fun `Non-recommended images are OK if they are only used during build`() {
        val filesToCheck = mapOf(
            "Dockerfile" to """
               FROM yolo AS builder
               COPY /from /to
               FROM cgr.dev/chainguard/go:latest
               RUN echo "hello"
            """.trimIndent()
        )
        val check = DistrolessCheck()
        val results = check.run("navikt/bogusrepo", filesToCheck)
        assertTrue(results is CheckResult.AllGood)
    }

    @Test
    fun `Non-recommended images are not OK if they are used @ runtime`() {
        val filesToCheck = mapOf(
            "Dockerfile" to """
               FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/thing:1.0 AS builder
               COPY . .
               FROM nginx:1.28
               RUN echo "hello"
            """.trimIndent()
        )
        val check = DistrolessCheck()
        val results = check.run("navikt/bogusrepo", filesToCheck)
        assertTrue(results is CheckResult.NeedsWork)
    }

    @Test
    fun `We are unable to check if the image name is templated `() {
        val filesToCheck = mapOf(
            "Dockerfile" to """
               FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/thing:1.0 AS builder
               COPY . .
               FROM { templated }
               RUN echo "hello"
            """.trimIndent()
        )
        val check = DistrolessCheck()
        val results = check.run("navikt/bogusrepo", filesToCheck)
        assertTrue(results is CheckResult.AllGood)
    }

}