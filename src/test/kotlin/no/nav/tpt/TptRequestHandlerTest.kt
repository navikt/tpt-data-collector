package no.nav.tpt

import java.io.IOException
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import no.nav.checks.CheckResult
import no.nav.checks.Checks
import no.nav.datastore.Datastore
import no.nav.github.FakeGitHub
import no.nav.github.GitHubFileInventoryException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TptRequestHandlerTest {
    private class RecordingDatastore : Datastore {
        val checkedRepos = mutableListOf<String>()
        override suspend fun ping() = true
        override fun activeDeploymentsFor(nameWithOwner: String): List<Triple<String, String, Instant>> {
            checkedRepos += nameWithOwner
            return emptyList()
        }
    }

    @Test
    fun `inventory failures skip checks and publication but do not stop subsequent repositories`() = runBlocking {
        for (failure in listOf(
            IOException("GitHub unavailable"),
            GitHubFileInventoryException("navikt/failed", "non-recursive tree response is truncated"),
            GitHubFileInventoryException("navikt/failed", "tree fetch budget exceeded"),
            GitHubFileInventoryException("navikt/failed", "inventory time budget exceeded")
        )) {
            val datastore = RecordingDatastore()
            val backend = FakeTptBackend()
            val listingAttempts = mutableListOf<String>()
            val gitHub = object : FakeGitHub() {
                override suspend fun allReposForTeam(teamName: String) = listOf("navikt/failed", "navikt/next")
                override suspend fun allFilePathsIn(nameWithOwner: String): List<String> {
                    listingAttempts += nameWithOwner
                    if (nameWithOwner == "navikt/failed") throw failure
                    return listOf(".gitignore")
                }
            }

            TptRequestHandler(gitHub, Checks(gitHub, datastore), backend).runAllChecksFor("team")

            assertEquals(listOf("navikt/failed", "navikt/next"), listingAttempts)
            assertEquals(listOf("navikt/next"), datastore.checkedRepos)
            assertEquals(listOf("navikt/next"), backend.sentCheckResults.map { it.nameWithOwner })
            assertEquals(listOf("team"), backend.sentCheckResults.single().repoOwners)
            assertTrue(backend.sentCheckResults.single().results
                .single { it.name == "HasGitignoreCheck" } is CheckResult.AllGood)
        }
    }

    @Test
    fun `genuinely empty inventories still run checks and publish missing gitignore`() = runBlocking {
        val datastore = RecordingDatastore()
        val backend = FakeTptBackend()
        val gitHub = object : FakeGitHub() {
            override suspend fun allReposForTeam(teamName: String) = listOf("navikt/empty")
        }

        TptRequestHandler(gitHub, Checks(gitHub, datastore), backend).runAllChecksFor("team")

        assertEquals(listOf("navikt/empty"), datastore.checkedRepos)
        val results = backend.sentCheckResults.single()
        assertEquals("navikt/empty", results.nameWithOwner)
        assertTrue(results.results.single { it.name == "HasGitignoreCheck" } is CheckResult.NeedsWork)
    }

    @Test
    fun `cancellation propagates without checks publication or processing another repository`() {
        val datastore = RecordingDatastore()
        val backend = FakeTptBackend()
        val listingAttempts = mutableListOf<String>()
        val gitHub = object : FakeGitHub() {
            override suspend fun allReposForTeam(teamName: String) = listOf("navikt/cancelled", "navikt/next")
            override suspend fun allFilePathsIn(nameWithOwner: String): List<String> {
                listingAttempts += nameWithOwner
                throw CancellationException("Collection cancelled")
            }
        }

        assertThrows<CancellationException> {
            runBlocking { TptRequestHandler(gitHub, Checks(gitHub, datastore), backend).runAllChecksFor("team") }
        }

        assertEquals(listOf("navikt/cancelled"), listingAttempts)
        assertTrue(datastore.checkedRepos.isEmpty())
        assertTrue(backend.sentCheckResults.isEmpty())
    }

    @Test
    fun `same repository name under different owners remains distinct throughout checks`() = runBlocking {
        val repositories = listOf("first-org/repo", "second-org/repo")
        val datastore = RecordingDatastore()
        val backend = FakeTptBackend()
        val inventoryRequests = mutableListOf<String>()
        val fileRequests = mutableListOf<String>()
        val apiRequests = mutableListOf<String>()
        val gitHub = object : FakeGitHub() {
            override suspend fun allReposForTeam(teamName: String) = repositories
            override suspend fun allFilePathsIn(nameWithOwner: String): List<String> {
                inventoryRequests += nameWithOwner
                return listOf(".gitignore")
            }
            override suspend fun readFileContents(nameWithOwner: String, filePath: String): String {
                fileRequests += nameWithOwner
                return ""
            }
            override suspend fun hasOpenCriticalDependabotAlerts(nameWithOwner: String): no.nav.github.DependabotAlertsResult {
                apiRequests += nameWithOwner
                return super.hasOpenCriticalDependabotAlerts(nameWithOwner)
            }
        }

        TptRequestHandler(gitHub, Checks(gitHub, datastore), backend).runAllChecksFor("team")

        assertEquals(repositories, inventoryRequests)
        assertEquals(repositories, fileRequests)
        assertEquals(repositories, apiRequests)
        assertEquals(repositories, datastore.checkedRepos)
        assertEquals(repositories, backend.sentCheckResults.map { it.nameWithOwner })
    }
}
