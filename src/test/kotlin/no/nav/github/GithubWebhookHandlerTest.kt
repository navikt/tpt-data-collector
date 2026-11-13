package no.nav.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
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
                assertEquals("repo", message.contextData.getValue<String>("repository"))
                assertNull(message.thrown)
            }
        }
    }

    @Test
    fun `lookup failures retain error logging and publication without owners`() = runTest {
        withCapturedLogs { events ->
            val failure = IllegalStateException("Whodis lookup failed")
            val whodis = object : FakeWhodis() {
                override suspend fun ownerTeamsFor(repo: String): List<String> = throw failure
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
            assertEquals("repo", message.contextData.getValue<String>("repository"))
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
        val handler = GithubWebhookHandler(
            Checks(FakeGitHub(), FakeDatastore()), backend, FakeWhodis(), organization,
        )

        handler.handle(payload("$payloadOrganization/repo", defaultBranch, defaultBranch))

        val result = backend.sentCheckResults.single()
        assertEquals("repo", result.repoName)
        assertEquals(listOf("tulleteam"), result.repoOwners)
        assertTrue(result.results.isNotEmpty())
    }

    @ParameterizedTest
    @CsvSource(
        "navikt/repo, main",
        "other-org/repo, main",
        "another-org-extra/repo, main",
        "ANOTHER-ORG-EXTRA/repo, main",
        "another-org, main",
        "another-org/repo, feature",
        "ANOTHER-ORG/repo, Main",
    )
    fun `other organizations and non default branches do not trigger checks`(fullName: String, branch: String) = runTest {
        val backend = FakeTptBackend()
        val ownerLookups = mutableListOf<String>()
        val whodis = object : FakeWhodis() {
            override suspend fun ownerTeamsFor(repo: String): List<String> {
                ownerLookups += repo
                return super.ownerTeamsFor(repo)
            }
        }
        val handler = GithubWebhookHandler(
            Checks(FakeGitHub(), FakeDatastore()), backend, whodis, "another-org",
        )

        handler.handle(payload(fullName, branch))

        assertTrue(ownerLookups.isEmpty())
        assertTrue(backend.sentCheckResults.isEmpty())
    }

    private fun payload(fullName: String, branch: String, defaultBranch: String = "main") =
        WebhookPayload(
            ref = "refs/heads/$branch",
            after = "commit",
            repository = Repository(1L, "repo", fullName, defaultBranch),
            commits = emptyList(),
        )
}
