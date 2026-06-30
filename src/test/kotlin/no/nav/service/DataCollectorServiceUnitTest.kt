package no.nav.service

import java.io.IOException
import no.nav.bigquery.BigQueryClientInterface
import no.nav.github.AnalysisTool
import no.nav.github.CodeScanningAnalysis
import no.nav.github.DummyGithubRepositoryClient
import no.nav.github.GithubCodeScanningClientInterface
import no.nav.github.GithubGitTreeClientInterface
import no.nav.github.GithubRepositoryContentsClientInterface
import no.nav.github.GithubRequestErrorKind
import no.nav.github.GithubRequestException
import no.nav.github.StaticGithubTokenProvider
import no.nav.kafka.DummyKafkaSender
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataCollectorServiceUnitTest {
    private val bigQueryClient = object : BigQueryClientInterface {
        override fun isAlive(): Boolean = true
        override fun readTable(tableName: String): List<Map<String, String>> = emptyList()
    }

    private fun bigQueryWithRepos(vararg repos: Map<String, String>) = object : BigQueryClientInterface {
        override fun isAlive(): Boolean = true
        override fun readTable(tableName: String): List<Map<String, String>> = repos.toList()
    }

    private fun repo(repoId: String, fullName: String) = mapOf("repo_id" to repoId, "full_name" to fullName)

    @Test
    fun `Should continue processing other dockerfiles after IO failure`() {
        val kafkaSender = DummyKafkaSender()
        val githubRepositoryClient = object : GithubRepositoryContentsClientInterface, GithubGitTreeClientInterface {
            override fun readFile(owner: String, repo: String, path: String, ref: String): String {
                if (path == "broken/Dockerfile") {
                    throw IOException("network timeout")
                }
                return "FROM ghcr.io/navikt/baseimages/temurin:21"
            }

            override fun listBlobPaths(owner: String, repo: String, ref: String): Set<String> = emptySet()
        }
        val service = DataCollectorService(
            bigQueryClient = bigQueryClient,
            kafkaSender = kafkaSender,
            githubTokenProvider = StaticGithubTokenProvider("dummy"),
            zizmorCommand = "TESTING",
            githubContentsClient = githubRepositoryClient,
            githubTreeClient = githubRepositoryClient,
            githubCodeScanningClient = DummyGithubRepositoryClient(),
        )

        val processedCount = service.processChangedDockerfilesAndSendToKafka(
            repoId = "12345",
            repoFullName = "navikt/demo",
            ref = "abcdef123456",
            candidatePaths = setOf("broken/Dockerfile", "Dockerfile"),
        )

        assertEquals(1, processedCount)
        assertEquals(1, kafkaSender.sentMessages.size)
        assertTrue(kafkaSender.sentMessages.single().second.contains("\"path\":\"Dockerfile\""))
    }

    @Test
    fun `processCodeScanningTools - empty repos table returns 0 and sends nothing`() {
        val kafkaSender = DummyKafkaSender()
        val service = makeService(kafkaSender = kafkaSender, bigQuery = bigQueryWithRepos())

        val result = service.processCodeScanningToolsAndSendToKafka()

        assertEquals(0, result)
        assertTrue(kafkaSender.sentMessages.isEmpty())
    }

    @Test
    fun `processCodeScanningTools - repo without repo_id is skipped`() {
        val kafkaSender = DummyKafkaSender()
        val service = makeService(
            kafkaSender = kafkaSender,
            bigQuery = bigQueryWithRepos(mapOf("full_name" to "navikt/demo")),
        )

        val result = service.processCodeScanningToolsAndSendToKafka()

        assertEquals(0, result)
        assertTrue(kafkaSender.sentMessages.isEmpty())
    }

    @Test
    fun `processCodeScanningTools - repo without full_name is skipped`() {
        val kafkaSender = DummyKafkaSender()
        val service = makeService(
            kafkaSender = kafkaSender,
            bigQuery = bigQueryWithRepos(mapOf("repo_id" to "123")),
        )

        val result = service.processCodeScanningToolsAndSendToKafka()

        assertEquals(0, result)
        assertTrue(kafkaSender.sentMessages.isEmpty())
    }

    @Test
    fun `processCodeScanningTools - publishes to code_scanning_tools Kafka topic`() {
        val kafkaSender = DummyKafkaSender()
        val service = makeService(
            kafkaSender = kafkaSender,
            bigQuery = bigQueryWithRepos(repo("123", "navikt/demo")),
        )

        service.processCodeScanningToolsAndSendToKafka()

        assertEquals("code_scanning_tools", kafkaSender.sentMessages.single().first)
    }

    @Test
    fun `processCodeScanningTools - payload contains repoId and repoName`() {
        val kafkaSender = DummyKafkaSender()
        val service = makeService(
            kafkaSender = kafkaSender,
            bigQuery = bigQueryWithRepos(repo("42", "navikt/my-repo")),
        )

        service.processCodeScanningToolsAndSendToKafka()

        val payload = kafkaSender.sentMessages.single().second
        assertTrue(payload.contains("\"repoId\":\"42\""))
        assertTrue(payload.contains("\"repoName\":\"navikt/my-repo\""))
    }

    @Test
    fun `processCodeScanningTools - continues processing after GitHub failure for one repo`() {
        val kafkaSender = DummyKafkaSender()
        val failingClient = object : GithubCodeScanningClientInterface {
            override fun getLatestAnalyses(owner: String, repo: String): List<CodeScanningAnalysis> {
                if (repo == "failing-repo") throw GithubRequestException(
                    operation = "code-scanning.analyses.list",
                    path = "/repos/navikt/failing-repo/code-scanning/analyses",
                    statusCode = 403,
                    kind = GithubRequestErrorKind.PERMANENT,
                    message = "Forbidden",
                )
                return emptyList()
            }
        }
        val service = makeService(
            kafkaSender = kafkaSender,
            bigQuery = bigQueryWithRepos(
                repo("1", "navikt/failing-repo"),
                repo("2", "navikt/ok-repo"),
            ),
            codeScanningClient = failingClient,
        )

        val result = service.processCodeScanningToolsAndSendToKafka()

        assertEquals(1, result)
        assertEquals(1, kafkaSender.sentMessages.size)
    }

    @Test
    fun `processCodeScanningTools - tools from analyses are included in payload`() {
        val kafkaSender = DummyKafkaSender()
        val analyses = listOf(
            CodeScanningAnalysis(
                tool = AnalysisTool(name = "CodeQL"),
                createdAt = "2026-06-28T04:00:00Z",
                resultsCount = 3,
                error = "",
            ),
        )
        val service = makeService(
            kafkaSender = kafkaSender,
            bigQuery = bigQueryWithRepos(repo("1", "navikt/demo")),
            codeScanningClient = DummyGithubRepositoryClient(analysesPerRepo = mapOf("demo" to analyses)),
        )

        service.processCodeScanningToolsAndSendToKafka()

        val payload = kafkaSender.sentMessages.single().second
        assertTrue(payload.contains("\"CodeQL\""))
        assertTrue(payload.contains("\"ok\""))
    }

    private fun makeService(
        kafkaSender: DummyKafkaSender = DummyKafkaSender(),
        bigQuery: BigQueryClientInterface = bigQueryClient,
        codeScanningClient: GithubCodeScanningClientInterface = DummyGithubRepositoryClient(),
    ) = DataCollectorService(
        bigQueryClient = bigQuery,
        kafkaSender = kafkaSender,
        githubTokenProvider = StaticGithubTokenProvider("dummy"),
        zizmorCommand = "TESTING",
        githubContentsClient = DummyGithubRepositoryClient(),
        githubTreeClient = DummyGithubRepositoryClient(),
        githubCodeScanningClient = codeScanningClient,
    )
}
