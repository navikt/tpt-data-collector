package no.nav.service

import java.io.IOException
import no.nav.bigquery.BigQueryClientInterface
import no.nav.github.GithubGitTreeClientInterface
import no.nav.github.GithubRepositoryContentsClientInterface
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
}
