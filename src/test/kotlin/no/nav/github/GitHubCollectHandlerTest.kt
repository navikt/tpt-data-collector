package no.nav.github

import kotlinx.coroutines.runBlocking
import no.nav.FakeWhodis
import no.nav.kafka.DummyKafkaSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json

class GitHubCollectHandlerTest {

    private val fakeGitHub = object : FakeGitHub() {
        override suspend fun vulnerabilityAlertsFor(owner: String, repo: String): List<VulnerabilityAlertNode> = listOf(
            VulnerabilityAlertNode(
                dependencyScope = "RUNTIME",
                dependabotUpdate = DependabotUpdateInfo(PullRequestInfo("https://github.com/navikt/fake-repo/pull/1")),
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
        )
    }

    @Test
    fun `collects repos from teams via whodis and publishes to kafka`() = runBlocking {
        val kafka = DummyKafkaSender()
        val handler = GitHubCollectHandler(fakeGitHub, FakeWhodis(), kafka)
        handler.collect(GitHubCollectRequest(teams = listOf("my-team")))

        val dataMessages = kafka.sentMessages.filter { it.first == "github_vulnerability_data" }
        assertEquals(1, dataMessages.size)
        val (key, value) = dataMessages.first()
        assertEquals("github_vulnerability_data", key)
        val message = Json.decodeFromString<GitHubRepositoryMessage>(value)
        assertEquals("navikt/fake-repo", message.nameWithOwner)
        assertEquals(listOf("my-team"), message.naisTeams)
        assertEquals(1, message.vulnerabilities.size)
        assertEquals("CRITICAL", message.vulnerabilities.first().severity)
    }

    @Test
    fun `repos with no vulnerabilities still produce a message with empty list`() = runBlocking {
        val kafka = DummyKafkaSender()
        val emptyGitHub = object : FakeGitHub() {
            override suspend fun vulnerabilityAlertsFor(owner: String, repo: String) = emptyList<VulnerabilityAlertNode>()
        }
        val handler = GitHubCollectHandler(emptyGitHub, FakeWhodis(), kafka)
        handler.collect(GitHubCollectRequest(repositories = listOf("navikt/some-repo")))

        assertEquals(1, kafka.sentMessages.count { it.first == "github_vulnerability_data" })
        val message = Json.decodeFromString<GitHubRepositoryMessage>(
            kafka.sentMessages.first { it.first == "github_vulnerability_data" }.second
        )
        assertEquals("navikt/some-repo", message.nameWithOwner)
        assertTrue(message.vulnerabilities.isEmpty())
    }

    @Test
    fun `publishes GITHUB_VULN_SYNC_STARTED before processing and GITHUB_VULN_SYNC_COMPLETE after`() = runBlocking {
        val kafka = DummyKafkaSender()
        val handler = GitHubCollectHandler(fakeGitHub, FakeWhodis(), kafka)
        handler.collect(GitHubCollectRequest(teams = listOf("appsec", "delta")))

        val keys = kafka.sentMessages.map { it.first }
        assertEquals("github_vuln_sync_started", keys.first())
        assertEquals("github_vuln_sync_complete", keys.last())

        val startedPayload = Json.decodeFromString<GitHubSyncEvent>(kafka.sentMessages.first().second)
        assertEquals(listOf("appsec", "delta"), startedPayload.teams.sorted())

        val completedPayload = Json.decodeFromString<GitHubSyncEvent>(kafka.sentMessages.last().second)
        assertEquals(listOf("appsec", "delta"), completedPayload.teams.sorted())
    }

    @Test
    fun `GITHUB_VULN_SYNC_COMPLETE is only emitted once after all repos are processed`() = runBlocking {
        val kafka = DummyKafkaSender()
        val whodis = object : FakeWhodis() {
            override suspend fun repositoriesForTeam(teamSlug: String) = listOf("navikt/repo-$teamSlug")
        }
        val handler = GitHubCollectHandler(fakeGitHub, whodis, kafka)
        handler.collect(GitHubCollectRequest(teams = listOf("team-a", "team-b")))

        assertEquals(1, kafka.sentMessages.count { it.first == "github_vuln_sync_complete" })
        assertEquals("github_vuln_sync_complete", kafka.sentMessages.last().first)
    }

    @Test
    fun `deduplicates repos appearing under multiple teams`() = runBlocking {
        val kafka = DummyKafkaSender()
        val whodis = object : FakeWhodis() {
            override suspend fun repositoriesForTeam(teamSlug: String) = listOf("navikt/shared-repo")
        }
        val handler = GitHubCollectHandler(fakeGitHub, whodis, kafka)
        handler.collect(GitHubCollectRequest(teams = listOf("team-a", "team-b")))

        // Only one message for the shared repo
        assertEquals(1, kafka.sentMessages.count { it.first == "github_vulnerability_data" })
        val message = Json.decodeFromString<GitHubRepositoryMessage>(
            kafka.sentMessages.first { it.first == "github_vulnerability_data" }.second
        )
        assertEquals("navikt/shared-repo", message.nameWithOwner)
        assertEquals(listOf("team-a", "team-b"), message.naisTeams.sorted())
    }
}
