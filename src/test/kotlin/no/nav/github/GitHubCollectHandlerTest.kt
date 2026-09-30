package no.nav.github

import kotlinx.coroutines.runBlocking
import no.nav.tpt.FakeTptBackend
import no.nav.tpt.SYNC_COMPLETE
import no.nav.tpt.SYNC_STARTED
import no.nav.tpt.VULNERABILITY_DATA
import no.nav.whodis.FakeWhodis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GitHubCollectHandlerTest {

    private fun alert(pullRequest: PullRequestInfo?) = VulnerabilityAlertNode(
        dependencyScope = "RUNTIME",
        dependabotUpdate = DependabotUpdateInfo(pullRequest),
        securityAdvisory = GraphQLSecurityAdvisory(
            publishedAt = null,
            cvss = CvssInfo(9.8),
            summary = "Critical vuln",
            identifiers = listOf(VulnerabilityIdentifier("CVE-2024-1234", "CVE"))
        ),
        securityVulnerability = GraphQLSecurityVulnerability(
            severity = "CRITICAL",
            pkg = GraphQLPackage("npm", "some-package")
        )
    )

    private val fakeGitHub = object : FakeGitHub() {
        override suspend fun vulnerabilityAlertsFor(owner: String, repo: String): List<VulnerabilityAlertNode> =
            listOf(alert(PullRequestInfo("https://github.com/navikt/fake-repo/pull/1", "OPEN")))
    }

    @Test
    fun `collects repos from teams via whodis and sends them to the backend`() = runBlocking {
        val backend = FakeTptBackend()
        val handler = GitHubCollectHandler(fakeGitHub, FakeWhodis(), backend)
        handler.collect(GitHubCollectRequest(teams = listOf("my-team")))

        assertEquals(1, backend.sentVulnerabilityData.size)
        val message = backend.sentVulnerabilityData.first()
        assertEquals("navikt/fake-repo", message.nameWithOwner)
        assertEquals(listOf("my-team"), message.naisTeams)
        assertEquals(1, message.vulnerabilities.size)
        assertEquals("CRITICAL", message.vulnerabilities.first().severity)
        assertEquals("https://github.com/navikt/fake-repo/pull/1", message.vulnerabilities.first().dependabotUpdatePullRequestUrl)
    }

    @Test
    fun `only includes dependabot pull request url when the pull request is open`() = runBlocking {
        val backend = FakeTptBackend()
        val gitHub = object : FakeGitHub() {
            override suspend fun vulnerabilityAlertsFor(owner: String, repo: String) = listOf(
                alert(PullRequestInfo("https://github.com/navikt/some-repo/pull/1", "OPEN")),
                alert(PullRequestInfo("https://github.com/navikt/some-repo/pull/2", "CLOSED")),
                alert(PullRequestInfo("https://github.com/navikt/some-repo/pull/3", "MERGED")),
                alert(null)
            )
        }
        val handler = GitHubCollectHandler(gitHub, FakeWhodis(), backend)
        handler.collect(GitHubCollectRequest(repositories = listOf("navikt/some-repo")))

        assertEquals(
            listOf("https://github.com/navikt/some-repo/pull/1", null, null, null),
            backend.sentVulnerabilityData.first().vulnerabilities.map { it.dependabotUpdatePullRequestUrl }
        )
    }

    @Test
    fun `repos with no vulnerabilities still produce a callback with empty list`() = runBlocking {
        val backend = FakeTptBackend()
        val emptyGitHub = object : FakeGitHub() {
            override suspend fun vulnerabilityAlertsFor(owner: String, repo: String) = emptyList<VulnerabilityAlertNode>()
        }
        val handler = GitHubCollectHandler(emptyGitHub, FakeWhodis(), backend)
        handler.collect(GitHubCollectRequest(repositories = listOf("navikt/some-repo")))

        assertEquals(1, backend.sentVulnerabilityData.size)
        val message = backend.sentVulnerabilityData.first()
        assertEquals("navikt/some-repo", message.nameWithOwner)
        assertTrue(message.vulnerabilities.isEmpty())
    }

    @Test
    fun `sends sync started before processing and sync complete after`() = runBlocking {
        val backend = FakeTptBackend()
        val handler = GitHubCollectHandler(fakeGitHub, FakeWhodis(), backend)
        handler.collect(GitHubCollectRequest(teams = listOf("appsec", "delta")))

        assertEquals(SYNC_STARTED, backend.callbackOrder.first())
        assertEquals(SYNC_COMPLETE, backend.callbackOrder.last())
        assertEquals(listOf("appsec", "delta"), backend.sentSyncStarted.single().teams.sorted())
        assertEquals(listOf("appsec", "delta"), backend.sentSyncComplete.single().teams.sorted())
    }

    @Test
    fun `sync complete is only sent once after all repos are processed`() = runBlocking {
        val backend = FakeTptBackend()
        val whodis = object : FakeWhodis() {
            override suspend fun repositoriesForTeam(teamSlug: String) = listOf("navikt/repo-$teamSlug")
        }
        val handler = GitHubCollectHandler(fakeGitHub, whodis, backend)
        handler.collect(GitHubCollectRequest(teams = listOf("team-a", "team-b")))

        assertEquals(listOf(SYNC_STARTED, VULNERABILITY_DATA, VULNERABILITY_DATA, SYNC_COMPLETE), backend.callbackOrder)
    }

    @Test
    fun `sync complete is sent even when collection throws`() {
        val backend = object : FakeTptBackend() {
            override suspend fun sendVulnerabilityData(message: GitHubRepositoryMessage): Boolean {
                super.sendVulnerabilityData(message)
                throw IllegalStateException("boom")
            }
        }
        val handler = GitHubCollectHandler(fakeGitHub, FakeWhodis(), backend)

        assertThrows<IllegalStateException> {
            runBlocking { handler.collect(GitHubCollectRequest(teams = listOf("my-team"))) }
        }
        assertEquals(listOf(SYNC_STARTED, VULNERABILITY_DATA, SYNC_COMPLETE), backend.callbackOrder)
        assertEquals(listOf("my-team"), backend.sentSyncComplete.single().teams)
    }

    @Test
    fun `sync complete is sent even when sync started throws`() {
        val backend = object : FakeTptBackend() {
            override suspend fun sendSyncStarted(event: GitHubSyncEvent): Boolean = throw IllegalStateException("boom")
        }
        val handler = GitHubCollectHandler(fakeGitHub, FakeWhodis(), backend)

        assertThrows<IllegalStateException> {
            runBlocking { handler.collect(GitHubCollectRequest(teams = listOf("my-team"))) }
        }
        assertEquals(1, backend.sentSyncComplete.size)
    }

    @Test
    fun `deduplicates repos appearing under multiple teams`() = runBlocking {
        val backend = FakeTptBackend()
        val whodis = object : FakeWhodis() {
            override suspend fun repositoriesForTeam(teamSlug: String) = listOf("navikt/shared-repo")
        }
        val handler = GitHubCollectHandler(fakeGitHub, whodis, backend)
        handler.collect(GitHubCollectRequest(teams = listOf("team-a", "team-b")))

        assertEquals(1, backend.sentVulnerabilityData.size)
        val message = backend.sentVulnerabilityData.first()
        assertEquals("navikt/shared-repo", message.nameWithOwner)
        assertEquals(listOf("team-a", "team-b"), message.naisTeams.sorted())
    }
}
