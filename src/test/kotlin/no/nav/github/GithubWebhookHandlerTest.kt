package no.nav.github

import kotlinx.coroutines.test.runTest
import no.nav.checks.Checks
import no.nav.datastore.FakeDatastore
import no.nav.tpt.FakeTptBackend
import no.nav.whodis.FakeWhodis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class GithubWebhookHandlerTest {
    @ParameterizedTest
    @CsvSource("navikt, main", "another-org, main", "another-org, master")
    fun `configured organization pushes to the default branch send unchanged repository results`(
        organization: String,
        defaultBranch: String,
    ) = runTest {
        val backend = FakeTptBackend()
        val handler = GithubWebhookHandler(
            Checks(FakeGitHub(), FakeDatastore()), backend, FakeWhodis(), organization,
        )

        handler.handle(payload("$organization/repo", defaultBranch, defaultBranch))

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
        "another-org, main",
        "another-org/repo, feature",
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
