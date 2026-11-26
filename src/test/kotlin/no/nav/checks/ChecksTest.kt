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
import no.nav.github.withCapturedLogs
import org.apache.logging.log4j.Level
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ChecksTest {
    private val workflow = ".github/workflows/build.yml"
    private val lockfile = ".github/workflows/actions.lock"
    private val unpinnedWorkflow = "steps:\n  - uses: actions/checkout@v4"

    @Test
    fun `removing the last readme reports needs work without fetching the deleted file`() = runBlocking {
        val gitHub = FileGitHub(emptyMap())

        val results = Checks(gitHub, FakeDatastore()).runAll("navikt/repo", setOf("README.md"))

        val readme = assertIs<CheckResult.NeedsWork>(results.single { it.name == "ReadmeCheck" })
        assertEquals(listOf("navikt/repo does not appear to have a Readme"), readme.reasons)
        assertTrue(gitHub.requestedFiles.isEmpty())
        assertEquals(1, gitHub.inventoryRequests)
    }

    @Test
    fun `removing the last workflow clears its findings without fetching the deleted file`() = runBlocking {
        val gitHub = FileGitHub(emptyMap())

        val results = Checks(gitHub, FakeDatastore()).runAll("navikt/repo", setOf(workflow))

        setOf("PinnedGitHubActionVersions", "PwnRequestCheck", "CurlPipeShell").forEach { name ->
            assertIs<CheckResult.AllGood>(results.single { it.name == name })
        }
        assertTrue(gitHub.requestedFiles.isEmpty())
        assertEquals(1, gitHub.inventoryRequests)
    }

    @Test
    fun `removing the last dockerfile clears its findings without fetching the deleted file`() = runBlocking {
        val gitHub = FileGitHub(emptyMap())

        val results = Checks(gitHub, FakeDatastore()).runAll("navikt/repo", setOf("Dockerfile"))

        setOf("DistrolessCheck", "CopyDotDot", "CurlPipeShell", "BaseImageIsNotPinnedCheck").forEach { name ->
            assertIs<CheckResult.AllGood>(results.single { it.name == name })
        }
        assertTrue(gitHub.requestedFiles.isEmpty())
        assertEquals(1, gitHub.inventoryRequests)
    }

    @Test
    fun `selected workflow checks include unchanged workflows and exclude absent changed paths`() = runBlocking<Unit> {
        val unchangedWorkflow = ".github/workflows/unchanged.yml"
        val gitHub = FileGitHub(mapOf(unchangedWorkflow to unpinnedWorkflow))

        val results = Checks(gitHub, FakeDatastore()).runAll("navikt/repo", setOf(workflow))

        val pinning = assertIs<CheckResult.NeedsWork>(results.single { it.name == "PinnedGitHubActionVersions" })
        assertEquals(listOf("Workflow '$unchangedWorkflow' uses non-pinned action versions"), pinning.reasons)
        assertEquals(listOf(unchangedWorkflow), gitHub.requestedFiles)
        assertEquals(1, gitHub.inventoryRequests)
        assertIs<CheckResult.AllGood>(results.single { it.name == "PwnRequestCheck" })
        assertIs<CheckResult.AllGood>(results.single { it.name == "CurlPipeShell" })
    }

    @Test
    fun `unchanged lockfile satisfies a workflow triggered pinning check`() = runBlocking {
        val gitHub = FileGitHub(mapOf(workflow to unpinnedWorkflow, lockfile to ""))

        val results = Checks(gitHub, FakeDatastore()).runAll("navikt/repo", setOf(workflow))

        assertIs<CheckResult.AllGood>(results.single { it.name == "PinnedGitHubActionVersions" })
        assertEquals(setOf(workflow, lockfile), gitHub.requestedFiles.toSet())
        assertEquals(gitHub.requestedFiles.size, gitHub.requestedFiles.toSet().size)
    }

    @Test
    fun `inventory does not select content checks without relevant changed paths`() = runBlocking<Unit> {
        val gitHub = FileGitHub(mapOf(workflow to unpinnedWorkflow, "README.md" to "Project\nDescription"))

        val results = Checks(gitHub, FakeDatastore()).runAll("navikt/repo", setOf("README.md"))

        assertEquals(listOf("README.md"), gitHub.requestedFiles)
        assertTrue(results.none { it.name == "PinnedGitHubActionVersions" })
        assertIs<CheckResult.AllGood>(results.single { it.name == "ReadmeCheck" })
    }

    @Test
    fun `supplied complete inventory is reused by all file checks`() = runBlocking {
        val gitHub = FileGitHub(mapOf(workflow to unpinnedWorkflow))

        Checks(gitHub, FakeDatastore()).runAll("navikt/repo", setOf(workflow), allFilesInRepo = gitHub.files.keys)

        assertEquals(0, gitHub.inventoryRequests)
        assertEquals(listOf(workflow), gitHub.requestedFiles)
    }

    @Test
    fun `inventory failure omits all dependent checks without fetching changed paths`() = runBlocking {
        val failure = IOException("Inventory unavailable")
        var inventoryRequests = 0
        val requestedFiles = mutableListOf<String>()
        val gitHub = object : FakeGitHub() {
            override suspend fun allFilePathsIn(nameWithOwner: String): List<String> {
                inventoryRequests++
                throw failure
            }

            override suspend fun readFileContents(nameWithOwner: String, filePath: String): String {
                requestedFiles += filePath
                return unpinnedWorkflow
            }
        }

        withCapturedLogs { events ->
            val results = Checks(gitHub, FakeDatastore()).runAll("navikt/repo", setOf(workflow))

            assertEquals(1, inventoryRequests)
            assertTrue(requestedFiles.isEmpty())
            assertTrue(results.none {
                it.name in setOf("HasGitignoreCheck", "PinnedGitHubActionVersions", "PwnRequestCheck", "CurlPipeShell")
            })
            assertTrue(results.any { it.name == "OldDeployments" })
            assertTrue(results.any { it.name == "HasCriticalVulns" })
            val warning = events.single { it.level == Level.WARN }
            assertEquals("file_inventory", warning.contextData.getValue<String>("operation"))
            assertEquals("navikt/repo", warning.contextData.getValue<String>("repository"))
            assertEquals(
                setOf("HasGitignoreCheck", "UnpinnedActionVersionsCheck", "PwnRequestCheck", "CurlPipeShellCheck"),
                warning.contextData.getValue<String>("affected_checks").split(",").toSet()
            )
        }
    }

    @Test
    fun `inventory cancellation propagates before content reads`() {
        val gitHub = object : FakeGitHub() {
            override suspend fun allFilePathsIn(nameWithOwner: String): List<String> =
                throw CancellationException("Collection cancelled")
        }

        assertThrows<CancellationException> {
            runBlocking { Checks(gitHub, FakeDatastore()).runAll("navikt/repo", setOf(workflow)) }
        }
    }

    @Test
    fun `shared file read failures are logged once with repository, filenames and affected checks`() = runBlocking {
        withCapturedLogs { events ->
            val gitHub = FileGitHub(mapOf(workflow to unpinnedWorkflow), failedFiles = setOf(workflow))
            Checks(gitHub, FakeDatastore()).runAll("navikt/repo", gitHub.files.keys)
            events.filter { it.level.isMoreSpecificThan(Level.WARN) }.forEach { event ->
                assertEquals(Level.WARN, event.level)
                assertEquals("navikt/repo", event.contextData.getValue<String>("repository"))
                assertTrue(event.contextData.getValue<String>("affected_checks").isNotEmpty())
                assertTrue(event.contextData.getValue<String>("file").isNotEmpty())
                assertNull(event.thrown)
            }
            val summary = events.single { it.message.formattedMessage == "Repository checks finished" }
            assertEquals("partial", summary.contextData.getValue<String>("outcome"))
            assertTrue(summary.contextData.getValue<String>("checks_failed").toInt() > 1)

        }
    }

    @Test
    fun `unexpected check failure retains the exception and names the repository and check`() = runBlocking {
        withCapturedLogs { events ->
            val cause = IllegalStateException("Unexpected API failure")
            val gitHub = object : FakeGitHub() {
                override suspend fun latestCodeScanningAnalysesFor(nameWithOwner: String): List<no.nav.github.GithubCodeScanningAnalysis> =
                    throw cause
            }
            Checks(gitHub, FakeDatastore()).runAll("navikt/repo", emptySet())

            val failure = events.single { it.level == Level.ERROR }
            assertEquals("navikt/repo", failure.contextData.getValue<String>("repository"))
            assertEquals("GithubToolingStatusCheck", failure.contextData.getValue<String>("check"))
            assertSame(cause, failure.thrown)
        }
    }

    @Test
    fun `unqualified repository names fail before checks run`() = runBlocking {
        val gitHub = FileGitHub(mapOf(".gitignore" to ""))

        assertThrows<IllegalArgumentException> {
            Checks(gitHub, FakeDatastore()).runAll("repo", gitHub.files.keys)
        }

        assertTrue(gitHub.requestedFiles.isEmpty())
    }

    @Test
    fun `missing lockfile is not fetched and unpinned actions need work`() = runBlocking {
        val gitHub = FileGitHub(mapOf(workflow to unpinnedWorkflow))

        val results = Checks(gitHub, FakeDatastore()).runAll("navikt/repo", gitHub.files.keys)
        assertTrue(results.single { it.name == "PinnedGitHubActionVersions" } is CheckResult.NeedsWork)
        assertTrue(gitHub.requestedFiles.containsAll(listOf(workflow)))
    }

    @Test
    fun `existing empty lockfile still satisfies the action pinning check`() = runBlocking {
        val gitHub = FileGitHub(mapOf(workflow to unpinnedWorkflow, lockfile to ""))

        val results = Checks(gitHub, FakeDatastore()).runAll("navikt/repo", gitHub.files.keys)

        assertIs<CheckResult.AllGood>(results.single { it.name == "PinnedGitHubActionVersions" })
        assertTrue(gitHub.requestedFiles.toSet().containsAll(setOf(workflow, lockfile)))
    }

    @Test
    fun `existing empty gitignore passes the presence check`() = runBlocking {
        val gitHub = FileGitHub(mapOf(".gitignore" to ""))

        val results = Checks(gitHub, FakeDatastore()).runAll("navikt/repo", gitHub.files.keys)

        assertIs<CheckResult.AllGood>(results.single { it.name == "HasGitignoreCheck" })
        assertTrue(gitHub.requestedFiles.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(ints = [404, 403, 500])
    fun `HTTP read failures omit affected checks but retain unrelated results`(status: Int) = runBlocking {
        HttpClient(MockEngine { respond("File read failed", HttpStatusCode.fromValue(status)) }) {
            expectSuccess = true
        }.use { client ->
            val gitHub = object : FakeGitHub() {
                override suspend fun allFilePathsIn(nameWithOwner: String) = listOf(workflow, "README.md")

                override suspend fun readFileContents(nameWithOwner: String, filePath: String): String {
                    if (filePath == workflow) {
                        client.get("https://api.github.com/repos/$nameWithOwner/contents/$filePath")
                        error("Expected the HTTP request to fail")
                    }
                    return "Project\nA descriptive readme."
                }
            }

            val results = Checks(gitHub, FakeDatastore()).runAll("navikt/repo", setOf(workflow, "README.md"))
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

        val results = Checks(gitHub, FakeDatastore()).runAll("navikt/repo", gitHub.files.keys)

        assertTrue(results.none { it.name == "PinnedGitHubActionVersions" })
        assertIs<CheckResult.AllGood>(results.single { it.name == "PwnRequestCheck" })
    }

    @Test
    fun `gitignore presence does not depend on readable contents`() = runBlocking {
        val gitHub = FileGitHub(mapOf(".gitignore" to "build/"), failedFiles = setOf(".gitignore"))

        val results = Checks(gitHub, FakeDatastore()).runAll("navikt/repo", emptySet())

        assertIs<CheckResult.AllGood>(results.single { it.name == "HasGitignoreCheck" })
        assertTrue(gitHub.requestedFiles.isEmpty())
        assertTrue(results.any { it.name == "OldDeployments" })
    }

    @Test
    fun `missing gitignore reports needs work without reading contents`() = runBlocking {
        val gitHub = FileGitHub(emptyMap())

        val results = Checks(gitHub, FakeDatastore()).runAll("navikt/repo", emptySet())

        assertIs<CheckResult.NeedsWork>(results.single { it.name == "HasGitignoreCheck" })
        assertTrue(gitHub.requestedFiles.isEmpty())
    }

    @Test
    fun `successful empty workflow is not treated as a failed read`() = runBlocking<Unit> {
        val gitHub = FileGitHub(mapOf(workflow to ""))

        val results = Checks(gitHub, FakeDatastore()).runAll("navikt/repo", gitHub.files.keys)

        assertIs<CheckResult.AllGood>(results.single { it.name == "PinnedGitHubActionVersions" })
    }

    @Test
    fun `file read cancellation propagates`() {
        val gitHub = object : FakeGitHub() {
            override suspend fun allFilePathsIn(nameWithOwner: String) = listOf(workflow)

            override suspend fun readFileContents(nameWithOwner: String, filePath: String): String {
                throw CancellationException("Collection cancelled")
            }
        }

        assertThrows<CancellationException> {
            runBlocking {
                Checks(gitHub, FakeDatastore()).runAll("navikt/repo", setOf(workflow))
            }
        }
    }

    private class FileGitHub(
        val files: Map<String, String>,
        private val failedFiles: Set<String> = emptySet()
    ) : FakeGitHub() {
        val requestedFiles = mutableListOf<String>()
        var inventoryRequests = 0

        override suspend fun allFilePathsIn(nameWithOwner: String): List<String> {
            inventoryRequests++
            return files.keys.toList()
        }

        override suspend fun readFileContents(nameWithOwner: String, filePath: String): String {
            requestedFiles += filePath
            if (filePath in failedFiles) throw IOException("File read failed")
            return files[filePath] ?: throw IOException("File not found")
        }
    }
}
