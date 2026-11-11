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
        override fun activeDeploymentsFor(originRepo: String): List<Triple<String, String, Instant>> {
            checkedRepos += originRepo
            return emptyList()
        }
    }

    @Test
    fun `inventory failures skip checks and publication but do not stop subsequent repositories`() = runBlocking {
        for (failure in listOf(
            IOException("GitHub unavailable"),
            GitHubFileInventoryException("failed", "non-recursive tree response is truncated"),
            GitHubFileInventoryException("failed", "tree fetch budget exceeded"),
            GitHubFileInventoryException("failed", "inventory time budget exceeded")
        )) {
            val datastore = RecordingDatastore()
            val backend = FakeTptBackend()
            val listingAttempts = mutableListOf<String>()
            val gitHub = object : FakeGitHub() {
                override suspend fun allReposForTeam(teamName: String) = listOf("failed", "next")
                override suspend fun allFilePathsIn(repoName: String): List<String> {
                    listingAttempts += repoName
                    if (repoName == "failed") throw failure
                    return listOf(".gitignore")
                }
            }

            TptRequestHandler(gitHub, Checks(gitHub, datastore), backend).runAllChecksFor("team")

            assertEquals(listOf("failed", "next"), listingAttempts)
            assertEquals(listOf("next"), datastore.checkedRepos)
            assertEquals(listOf("next"), backend.sentCheckResults.map { it.repoName })
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
            override suspend fun allReposForTeam(teamName: String) = listOf("empty")
        }

        TptRequestHandler(gitHub, Checks(gitHub, datastore), backend).runAllChecksFor("team")

        assertEquals(listOf("empty"), datastore.checkedRepos)
        val results = backend.sentCheckResults.single()
        assertEquals("empty", results.repoName)
        assertTrue(results.results.single { it.name == "HasGitignoreCheck" } is CheckResult.NeedsWork)
    }

    @Test
    fun `cancellation propagates without checks publication or processing another repository`() {
        val datastore = RecordingDatastore()
        val backend = FakeTptBackend()
        val listingAttempts = mutableListOf<String>()
        val gitHub = object : FakeGitHub() {
            override suspend fun allReposForTeam(teamName: String) = listOf("cancelled", "next")
            override suspend fun allFilePathsIn(repoName: String): List<String> {
                listingAttempts += repoName
                throw CancellationException("Collection cancelled")
            }
        }

        assertThrows<CancellationException> {
            runBlocking { TptRequestHandler(gitHub, Checks(gitHub, datastore), backend).runAllChecksFor("team") }
        }

        assertEquals(listOf("cancelled"), listingAttempts)
        assertTrue(datastore.checkedRepos.isEmpty())
        assertTrue(backend.sentCheckResults.isEmpty())
    }
}
