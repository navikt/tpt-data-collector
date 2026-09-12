package no.nav.checks.files

import kotlin.test.assertTrue
import no.nav.checks.CheckResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DependabotForAllEcosystemsCheckTest {

    @Test
    fun `This check should only care about certain files`() {
        val allAvailableFiles = setOf("Dockerfile", "/folder/go.mod", "pom.xml",
            "build.gradle.kts", "whatever", "irrelevant")
        val check = DependabotForAllEcosystemsCheck()
        val expected = listOf("Dockerfile", "/folder/go.mod", "pom.xml",
            "build.gradle.kts", ".github/dependabot.yml")
        val actual = check.filesICareAbout(allAvailableFiles)
        assertEquals(expected, actual)
    }

    @Test
    fun `ecosystems with no dependabot config should be flagged`() {
        val allAvailableFiles = mapOf(
            "Dockerfile" to "whatever",
            "/folder/go.mod" to "whatever",
            "pom.xml" to "whatever",
            "build.gradle.kts" to "whatever",
            ".github/dependabot.yml" to "package-ecosystem: docker\npackage-ecosystem: npm")
        val check = DependabotForAllEcosystemsCheck()
        val results = check.run("tullerepo", allAvailableFiles)
        assertTrue(results is CheckResult.NeedsWork)
        assertEquals(3, results.reasons.size)
    }

    @Test
    fun `all is good if all ecosystems are covered`() {
        val allAvailableFiles = mapOf(
            "/folder/go.mod" to "whatever",
            "pom.xml" to "whatever",
            ".github/dependabot.yml" to "package-ecosystem: gomod\npackage-ecosystem: maven")
        val check = DependabotForAllEcosystemsCheck()
        val results = check.run("tullerepo", allAvailableFiles)
        assertTrue(results is CheckResult.AllGood)
    }

    @Test
    fun `some repos may not have dependabot configured`() {
        val allAvailableFiles = mapOf(
            "/folder/go.mod" to "whatever",
            "pom.xml" to "whatever")
        val check = DependabotForAllEcosystemsCheck()
        val results = check.run("tullerepo", allAvailableFiles)
        assertTrue(results is CheckResult.NeedsWork)
        assertEquals(2, results.reasons.size)
    }

}