package no.nav.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import no.nav.checks.CheckResult
import no.nav.checks.Checks
import no.nav.datastore.FakeDatastore
import no.nav.tpt.FakeTptBackend
import no.nav.whodis.FakeWhodis
import no.nav.whodis.RealWhodis
import org.apache.logging.log4j.Level
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class GithubWebhookHandlerTest {
    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun `absent workflow from a stale or multi commit push is not fetched`(commitCount: Int) = runTest {
        val removedWorkflow = ".github/workflows/deploy-backend.yaml"
        val requestedFiles = mutableListOf<String>()
        HttpClient(MockEngine { respond("Not Found", HttpStatusCode.NotFound) }) {
            expectSuccess = true
        }.use { client ->
            val gitHub = object : FakeGitHub() {
                override suspend fun allFilePathsIn(nameWithOwner: String) = listOf(".gitignore")

                override suspend fun readFileContents(nameWithOwner: String, filePath: String): String {
                    requestedFiles += filePath
                    client.get("https://api.github.com/repos/$nameWithOwner/contents/$filePath")
                    error("Expected the HTTP request to fail")
                }
            }
            val backend = FakeTptBackend()
            val handler = GithubWebhookHandler(
                Checks(gitHub, FakeDatastore()), backend, FakeWhodis(), "navikt",
            )
            val push = WebhookPayload(
                ref = "refs/heads/main",
                after = "removed",
                repository = Repository(1L, "team-ksg", "navikt/team-ksg", "main"),
                commits = listOf(
                    Commit("modified", listOf(removedWorkflow), emptyList(), emptyList()),
                    Commit("removed", emptyList(), emptyList(), listOf(removedWorkflow)),
                ).take(commitCount),
            )

            withCapturedLogs { events ->
                handler.handle(push)

                val failures = events.filter {
                    it.message.formattedMessage.startsWith("File read failed")
                }
                assertTrue(requestedFiles.isEmpty(), "Nonexistent workflow was fetched: $requestedFiles; " +
                    "logged failures: ${failures.map { it.message.formattedMessage }}")
                assertTrue(failures.isEmpty())
                assertTrue(backend.sentCheckResults.single().results.isNotEmpty())
            }
        }
    }

    @Test
    fun `workflow removal triggers evaluation of remaining workflows`() = runTest {
        val remainingWorkflow = ".github/workflows/build.yml"
        val requestedFiles = mutableListOf<String>()
        val gitHub = object : FakeGitHub() {
            override suspend fun allFilePathsIn(nameWithOwner: String) = listOf(remainingWorkflow)

            override suspend fun readFileContents(nameWithOwner: String, filePath: String): String {
                requestedFiles += filePath
                assertEquals(remainingWorkflow, filePath)
                return "steps:\n  - uses: actions/checkout@v4"
            }
        }
        val backend = FakeTptBackend()
        val handler = GithubWebhookHandler(Checks(gitHub, FakeDatastore()), backend, FakeWhodis(), "navikt")
        val push = WebhookPayload(
            ref = "refs/heads/main",
            after = "removed",
            repository = Repository(1L, "repo", "navikt/repo", "main"),
            commits = listOf(
                Commit("removed", emptyList(), emptyList(), listOf(".github/workflows/deploy-backend.yaml")),
            ),
        )

        handler.handle(push)

        assertEquals(listOf(remainingWorkflow), requestedFiles)
        assertTrue(backend.sentCheckResults.single().results
            .single { it.name == "PinnedGitHubActionVersions" } is CheckResult.NeedsWork)
    }

    @ParameterizedTest
    @ValueSource(strings = ["null", "[]"])
    fun `missing owners publish checks with an informational message and no error`(body: String) = runTest {
        withCapturedLogs { events ->
            HttpClient(MockEngine {
                respond(body, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }) {
                install(ContentNegotiation) { json() }
                expectSuccess = true
            }.use { client ->
                val backend = FakeTptBackend()
                val handler = GithubWebhookHandler(
                    Checks(FakeGitHub(), FakeDatastore()), backend, RealWhodis(client, "http://whodis"), "navikt",
                )

                handler.handle(payload("navikt/repo", "main"))

                val result = backend.sentCheckResults.single()
                assertTrue(result.repoOwners.isEmpty())
                assertTrue(result.results.isNotEmpty())
                assertTrue(events.none { it.level.isMoreSpecificThan(Level.WARN) })
                val message = events.single {
                    it.message.formattedMessage == "No owning team found for repository"
                }
                assertEquals(Level.INFO, message.level)
                assertEquals("navikt/repo", message.contextData.getValue<String>("repository"))
                assertNull(message.thrown)
            }
        }
    }

    @Test
    fun `lookup failures retain error logging and publication without owners`() = runTest {
        withCapturedLogs { events ->
            val failure = IllegalStateException("Whodis lookup failed")
            val whodis = object : FakeWhodis() {
                override suspend fun ownerTeamsFor(nameWithOwner: String): List<String> = throw failure
            }
            val backend = FakeTptBackend()
            val handler = GithubWebhookHandler(
                Checks(FakeGitHub(), FakeDatastore()), backend, whodis, "navikt",
            )

            handler.handle(payload("navikt/repo", "main"))

            val result = backend.sentCheckResults.single()
            assertTrue(result.repoOwners.isEmpty())
            assertTrue(result.results.isNotEmpty())
            val message = events.single {
                it.message.formattedMessage == "Repository owner lookup failed; publishing checks without owners"
            }
            assertEquals(Level.ERROR, message.level)
            assertEquals("navikt/repo", message.contextData.getValue<String>("repository"))
            assertSame(failure, message.thrown)
            assertTrue(events.none { it.message.formattedMessage.startsWith("No owning team found") })
        }
    }

    @ParameterizedTest
    @CsvSource(
        "navikt, navikt, main",
        "another-org, another-org, main",
        "another-org, another-org, master",
        "Navikt, navikt, main",
        "navikt, Navikt, main",
        "Another-Org, aNoThEr-OrG, main",
    )
    fun `configured organization pushes to the default branch send unchanged repository results`(
        organization: String,
        payloadOrganization: String,
        defaultBranch: String,
    ) = runTest {
        val backend = FakeTptBackend()
        val ownerLookups = mutableListOf<String>()
        val whodis = object : FakeWhodis() {
            override suspend fun ownerTeamsFor(nameWithOwner: String): List<String> {
                ownerLookups += nameWithOwner
                return super.ownerTeamsFor(nameWithOwner)
            }
        }
        val handler = GithubWebhookHandler(
            Checks(FakeGitHub(), FakeDatastore()), backend, whodis, organization,
        )

        handler.handle(payload("$payloadOrganization/repo", defaultBranch, defaultBranch))

        val result = backend.sentCheckResults.single()
        assertEquals("$payloadOrganization/repo", result.nameWithOwner)
        assertEquals(listOf("$payloadOrganization/repo"), ownerLookups)
        assertEquals(listOf("tulleteam"), result.repoOwners)
        assertTrue(result.results.isNotEmpty())
    }

    @ParameterizedTest
    @CsvSource(
        "navikt/repo, main, other_organization",
        "other-org/repo, main, other_organization",
        "another-org-extra/repo, main, other_organization",
        "ANOTHER-ORG-EXTRA/repo, main, other_organization",
        "another-org, main, other_organization",
        "another-org/repo, feature, non_default_branch",
        "ANOTHER-ORG/repo, Main, non_default_branch",
    )
    fun `other organizations and non default branches do not trigger checks`(
        fullName: String,
        branch: String,
        reason: String,
    ) = runTest {
        val backend = FakeTptBackend()
        val ownerLookups = mutableListOf<String>()
        val whodis = object : FakeWhodis() {
            override suspend fun ownerTeamsFor(nameWithOwner: String): List<String> {
                ownerLookups += nameWithOwner
                return super.ownerTeamsFor(nameWithOwner)
            }
        }
        val handler = GithubWebhookHandler(
            Checks(FakeGitHub(), FakeDatastore()), backend, whodis, "another-org",
        )

        withCapturedLogs { events ->
            val deliveryId = "12345678-1234-1234-1234-123456789abc"
            handler.handle(payload(fullName, branch), deliveryId)

            assertTrue(ownerLookups.isEmpty())
            assertTrue(backend.sentCheckResults.isEmpty())
            val decision = events.single { it.message.formattedMessage == "GitHub webhook ignored" }
            assertEquals(Level.INFO, decision.level)
            assertEquals("github_webhook", decision.contextData.getValue<String>("operation"))
            assertEquals("ignored", decision.contextData.getValue<String>("outcome"))
            assertEquals(reason, decision.contextData.getValue<String>("reason"))
            assertEquals(fullName, decision.contextData.getValue<String>("repository"))
            assertEquals("refs/heads/$branch", decision.contextData.getValue<String>("ref"))
            assertEquals(deliveryId, decision.contextData.getValue<String>("delivery_id"))
        }
    }

    private fun payload(fullName: String, branch: String, defaultBranch: String = "main") =
        WebhookPayload(
            ref = "refs/heads/$branch",
            after = "commit",
            repository = Repository(1L, "repo", fullName, defaultBranch),
            commits = emptyList(),
        )
}
