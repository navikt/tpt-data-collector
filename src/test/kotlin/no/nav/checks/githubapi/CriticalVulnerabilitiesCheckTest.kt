package no.nav.checks.githubapi

import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import no.nav.checks.CheckResult
import no.nav.github.DependabotAlertsResult
import no.nav.github.FakeGitHub
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CriticalVulnerabilitiesCheckTest {

    @Test
    fun `Critical vulns should be flagged`() = runTest {
        val check = CriticalVulnerabilitiesCheck(FakeGitHub())
        val result = check.run("bogusrepo")
        assertTrue(result is CheckResult.NeedsWork)
        assertEquals(listOf("GitHub reports open critical vulnerabilities in bogusrepo"), result.reasons)
    }

    @Test
    fun `No vulns should pass`() = runTest {
        val fakeGitHub = object : FakeGitHub() {
            override suspend fun hasOpenCriticalDependabotAlerts(repoName: String) =
                DependabotAlertsResult.Available(false)
        }
        val result = CriticalVulnerabilitiesCheck(fakeGitHub).run("bogusrepo")
        assertTrue(result is CheckResult.AllGood)
    }

    @Test
    fun `Disabled alerts do not produce a vulnerability assessment`() = runTest {
        val fakeGitHub = object : FakeGitHub() {
            override suspend fun hasOpenCriticalDependabotAlerts(repoName: String) = DependabotAlertsResult.Disabled
        }

        assertNull(CriticalVulnerabilitiesCheck(fakeGitHub).run("bogusrepo"))
    }

}