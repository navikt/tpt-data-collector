package no.nav.checks.githubapi

import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import no.nav.checks.CheckResult
import no.nav.github.DependabotAlertsResult
import no.nav.github.FakeGitHub
import no.nav.github.withCapturedLogs
import org.apache.logging.log4j.Level
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CriticalVulnerabilitiesCheckTest {

    @Test
    fun `Critical vulns should be flagged`() = runTest {
        val check = CriticalVulnerabilitiesCheck(FakeGitHub())
        val result = check.run("another-org/bogusrepo")
        assertTrue(result is CheckResult.NeedsWork)
        assertEquals(listOf("GitHub reports open critical vulnerabilities in another-org/bogusrepo"), result.reasons)
    }

    @Test
    fun `No vulns should pass`() = runTest {
        val fakeGitHub = object : FakeGitHub() {
            override suspend fun hasOpenCriticalDependabotAlerts(nameWithOwner: String) =
                DependabotAlertsResult.Available(false)
        }
        val result = CriticalVulnerabilitiesCheck(fakeGitHub).run("another-org/bogusrepo")
        assertTrue(result is CheckResult.AllGood)
    }

    @Test
    fun `Disabled alerts do not produce a vulnerability assessment`() = runTest {
        val fakeGitHub = object : FakeGitHub() {
            override suspend fun hasOpenCriticalDependabotAlerts(nameWithOwner: String) = DependabotAlertsResult.Disabled
        }

        withCapturedLogs { events ->
            assertNull(CriticalVulnerabilitiesCheck(fakeGitHub).run("another-org/bogusrepo"))
            val skipped = events.single {
                it.message.formattedMessage == "Check unavailable; Dependabot alerts are disabled"
            }
            assertEquals(Level.INFO, skipped.level)
            assertEquals("another-org/bogusrepo", skipped.contextData.getValue<String>("repository"))
            assertEquals("HasCriticalVulns", skipped.contextData.getValue<String>("check"))
            assertNull(skipped.thrown)
        }
    }

}