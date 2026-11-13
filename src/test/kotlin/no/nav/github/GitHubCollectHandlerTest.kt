package no.nav.github

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import no.nav.tpt.FakeTptBackend
import no.nav.tpt.SYNC_COMPLETE
import no.nav.tpt.SYNC_STARTED
import no.nav.tpt.VULNERABILITY_DATA
import no.nav.whodis.FakeWhodis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.apache.logging.log4j.Level
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GitHubCollectHandlerTest {

    @Test
    fun `incomplete collection logs context and impact without a stack trace`() = runBlocking {
        withCapturedLogs(GitHubCollectHandler::class.java.name) { events ->
            val gitHub = object : FakeGitHub() {
                override suspend fun vulnerabilityAlertsFor(owner: String, repo: String): List<VulnerabilityAlertNode> =
                    throw GitHubVulnerabilityCollectionException(owner, repo, "GraphQL data is null or missing")
            }
            val backend = FakeTptBackend()
            GitHubCollectHandler(gitHub, FakeWhodis(), backend).collect(
                GitHubCollectRequest(repositories = listOf("navikt/repo"))
            )

            val failure = events.single { it.level.isMoreSpecificThan(Level.WARN) }
            assertEquals(Level.WARN, failure.level)
            assertEquals("navikt/repo", failure.contextData.getValue<String>("repository"))
            assertEquals("vulnerability_alerts", failure.contextData.getValue<String>("operation"))
            assertTrue(failure.message.formattedMessage.contains("repository update not sent"))
            assertEquals("GraphQL data is null or missing", failure.contextData.getValue<String>("reason"))
            assertNull(failure.thrown)
            assertTrue(backend.sentVulnerabilityData.isEmpty())
        }
    }

    @Test
    fun `unexpected collection failures retain their stack trace and repository context`() = runBlocking {
        withCapturedLogs(GitHubCollectHandler::class.java.name) { events ->
            val cause = IllegalStateException("Unexpected failure")
            val gitHub = object : FakeGitHub() {
                override suspend fun vulnerabilityAlertsFor(owner: String, repo: String): List<VulnerabilityAlertNode> =
                    throw cause
            }
            GitHubCollectHandler(gitHub, FakeWhodis(), FakeTptBackend()).collect(
                GitHubCollectRequest(repositories = listOf("navikt/repo"))
            )

            val failure = events.single { it.level == Level.ERROR }
            assertEquals("navikt/repo", failure.contextData.getValue<String>("repository"))
            assertSame(cause, failure.thrown)
        }
    }

    @Test
    fun `failed sync delivery is never logged as sent or collection as successful`() = runBlocking {
        withCapturedLogs(GitHubCollectHandler::class.java.name) { events ->
            val backend = object : FakeTptBackend() {
                override suspend fun sendSyncStarted(event: GitHubSyncEvent) = false
                override suspend fun sendSyncComplete(event: GitHubSyncEvent) = false
                override suspend fun sendVulnerabilityData(message: GitHubRepositoryMessage) = false
            }
            GitHubCollectHandler(fakeGitHub, FakeWhodis(), backend).collect(
                GitHubCollectRequest(repositories = listOf("navikt/repo"))
            )

            assertFalse(events.any { it.message.formattedMessage.startsWith("Sent") })
            val summary = events.single { it.message.formattedMessage == "GitHub collection finished" }
            assertEquals("partial", summary.contextData.getValue<String>("outcome"))
            assertEquals("0", summary.contextData.getValue<String>("repositories_sent"))
            assertEquals("1", summary.contextData.getValue<String>("repositories_failed"))
            assertEquals("false", summary.contextData.getValue<String>("sync_started_sent"))
            assertEquals("false", summary.contextData.getValue<String>("sync_complete_sent"))
        }
    }

    @Test
    fun `cancellation during team lookup propagates without failure logging and still sends completion`() = runBlocking {
        withCapturedLogs(GitHubCollectHandler::class.java.name) { events ->
            val backend = FakeTptBackend()
            val whodis = object : FakeWhodis() {
                override suspend fun repositoriesForTeam(teamSlug: String): List<String> =
                    throw CancellationException("Cancelled")
            }
            assertThrows<CancellationException> {
                GitHubCollectHandler(fakeGitHub, whodis, backend).collect(GitHubCollectRequest(teams = listOf("team")))
            }
            assertEquals(1, backend.sentSyncComplete.size)
            assertTrue(events.none { it.level.isMoreSpecificThan(Level.WARN) })
            assertEquals("cancelled", events.last().contextData.getValue<String>("outcome"))
        }
    }

    @Test
    fun `cancellation during alert collection stops before the next repository`() = runBlocking {
        withCapturedLogs(GitHubCollectHandler::class.java.name) { events ->
            val requested = mutableListOf<String>()
            val backend = FakeTptBackend()
            val gitHub = object : FakeGitHub() {
                override suspend fun vulnerabilityAlertsFor(owner: String, repo: String): List<VulnerabilityAlertNode> {
                    requested += repo
                    throw CancellationException("Cancelled")
                }
            }
            assertThrows<CancellationException> {
                GitHubCollectHandler(gitHub, FakeWhodis(), backend).collect(
                    GitHubCollectRequest(repositories = listOf("navikt/first", "navikt/second"))
                )
            }
            assertEquals(listOf("first"), requested)
            assertEquals(1, backend.sentSyncComplete.size)
            assertTrue(events.none { it.level.isMoreSpecificThan(Level.WARN) })
        }
    }

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
