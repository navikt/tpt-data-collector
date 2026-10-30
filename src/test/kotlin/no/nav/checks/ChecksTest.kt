package no.nav.checks

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import no.nav.datastore.FakeDatastore
import no.nav.github.FakeGitHub
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ChecksTest {
    private val workflow = ".github/workflows/build.yml"
    private val lockfile = ".github/workflows/actions.lock"
    private val unpinnedWorkflow = "steps:\n  - uses: actions/checkout@v4"

    @Test
    fun `missing lockfile is not fetched and unpinned actions need work`() = runBlocking {
        val gitHub = FileGitHub(mapOf(workflow to unpinnedWorkflow))

        val results = Checks(gitHub, FakeDatastore()).runAll("repo", gitHub.files.keys)

        assertIs<CheckResult.NeedsWork>(results.single { it.name == "PinnedGitHubActionVersions" })
        assertEquals(listOf(workflow), gitHub.requestedFiles)
    }

    @Test
    fun `existing empty lockfile still satisfies the action pinning check`() = runBlocking {
        val gitHub = FileGitHub(mapOf(workflow to unpinnedWorkflow, lockfile to ""))

        val results = Checks(gitHub, FakeDatastore()).runAll("repo", gitHub.files.keys)

        assertIs<CheckResult.AllGood>(results.single { it.name == "PinnedGitHubActionVersions" })
        assertEquals(setOf(workflow, lockfile), gitHub.requestedFiles.toSet())
    }

    @Test
    fun `missing gitignore needs work without fetching it`() = runBlocking {
        val gitHub = FileGitHub(emptyMap())

        val results = Checks(gitHub, FakeDatastore()).runAll("repo", emptySet())

        assertIs<CheckResult.NeedsWork>(results.single { it.name == "HasGitignoreCheck" })
        assertTrue(gitHub.requestedFiles.isEmpty())
    }

    @Test
    fun `existing empty gitignore passes the presence check`() = runBlocking {
        val gitHub = FileGitHub(mapOf(".gitignore" to ""))

        val results = Checks(gitHub, FakeDatastore()).runAll("repo", gitHub.files.keys)

        assertIs<CheckResult.AllGood>(results.single { it.name == "HasGitignoreCheck" })
        assertEquals(listOf(".gitignore"), gitHub.requestedFiles)
    }

    @ParameterizedTest
    @ValueSource(ints = [404, 403, 500])
    fun `HTTP read failures omit affected checks but retain unrelated results`(status: Int) = runBlocking {
        HttpClient(MockEngine { respond("File read failed", HttpStatusCode.fromValue(status)) }) {
            expectSuccess = true
        }.use { client ->
            val gitHub = object : FakeGitHub() {
                override suspend fun readFileContents(repoName: String, filePath: String): String {
                    if (filePath == workflow) {
                        client.get("https://api.github.com/repos/navikt/$repoName/contents/$filePath")
                        error("Expected the HTTP request to fail")
                    }
                    return "Project\nA descriptive readme."
                }
            }

            val results = Checks(gitHub, FakeDatastore()).runAll("repo", setOf(workflow, "README.md"))
            val names = results.map { it.name }.toSet()

            assertTrue("PinnedGitHubActionVersions" !in names)
            assertTrue("PwnRequestCheck" !in names)
            assertTrue("CurlPipeShell" !in names)
            assertIs<CheckResult.AllGood>(results.single { it.name == "ReadmeCheck" })
            assertTrue("OldDeployments" in names)
            assertTrue("HasCriticalVulns" in names)
        }
    }

    @Test
    fun `failed lockfile read does not pass pinning or prevent other workflow checks`() = runBlocking {
        val gitHub = FileGitHub(
            mapOf(workflow to unpinnedWorkflow, lockfile to "lock contents"),
            failedFiles = setOf(lockfile)
        )

        val results = Checks(gitHub, FakeDatastore()).runAll("repo", gitHub.files.keys)

        assertTrue(results.none { it.name == "PinnedGitHubActionVersions" })
        assertIs<CheckResult.AllGood>(results.single { it.name == "PwnRequestCheck" })
    }

    @Test
    fun `unreadable gitignore does not pass the presence check`() = runBlocking {
        val gitHub = FileGitHub(mapOf(".gitignore" to "build/"), failedFiles = setOf(".gitignore"))

        val results = Checks(gitHub, FakeDatastore()).runAll("repo", gitHub.files.keys)

        assertTrue(results.none { it.name == "HasGitignoreCheck" })
        assertTrue(results.any { it.name == "OldDeployments" })
    }

    @Test
    fun `successful empty workflow is not treated as a failed read`() = runBlocking {
        val gitHub = FileGitHub(mapOf(workflow to ""))

        val results = Checks(gitHub, FakeDatastore()).runAll("repo", gitHub.files.keys)

        assertIs<CheckResult.AllGood>(results.single { it.name == "PinnedGitHubActionVersions" })
    }

    @Test
    fun `file read cancellation propagates`() {
        val gitHub = object : FakeGitHub() {
            override suspend fun readFileContents(repoName: String, filePath: String): String {
                throw CancellationException("Collection cancelled")
            }
        }

        assertThrows<CancellationException> {
            runBlocking {
                Checks(gitHub, FakeDatastore()).runAll("repo", setOf(workflow))
            }
        }
    }

    private class FileGitHub(
        val files: Map<String, String>,
        private val failedFiles: Set<String> = emptySet()
    ) : FakeGitHub() {
        val requestedFiles = mutableListOf<String>()

        override suspend fun readFileContents(repoName: String, filePath: String): String {
            requestedFiles += filePath
            if (filePath in failedFiles) throw IOException("File read failed")
            return files[filePath] ?: throw IOException("File not found")
        }
    }
}
