package no.nav.checks.githubapi

import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import no.nav.checks.CheckResult
import no.nav.github.FakeGitHub
import org.junit.jupiter.api.Test

class CriticalVulnerabilitiesCheckTest {

    @Test
    fun `Critical vulns should be flagged`() = runTest {
        val check = CriticalVulnerabilitiesCheck(FakeGitHub())
        val result = check.run("bogusrepo")
        assertTrue(result is CheckResult.NeedsWork)
    }

}