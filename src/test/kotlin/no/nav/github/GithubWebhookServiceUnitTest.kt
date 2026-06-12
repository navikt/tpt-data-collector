package no.nav.github

import no.nav.bigquery.BigQueryClientInterface
import no.nav.generateHmac
import no.nav.github.DummyGithubRepositoryClient
import no.nav.github.StaticGithubTokenProvider
import no.nav.kafka.DummyKafkaSender
import no.nav.service.DataCollectorService
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GithubWebhookServiceUnitTest {
    private val bigQueryClient = object : BigQueryClientInterface {
        override fun isAlive(): Boolean = true
        override fun readTable(tableName: String): List<Map<String, String>> = emptyList()
    }

    @Test
    fun `Should fetch and publish changed dockerfiles once per unique path`() {
        val kafkaSender = DummyKafkaSender()
        val dataCollectorService = DataCollectorService(
            bigQueryClient = bigQueryClient,
            kafkaSender = kafkaSender,
            githubTokenProvider = StaticGithubTokenProvider("dummy"),
            zizmorCommand = "TESTING",
            githubContentsClient = DummyGithubRepositoryClient(
                mapOf(
                    "Dockerfile" to "FROM ghcr.io/navikt/baseimages/temurin:21\nADD app.jar /app/app.jar",
                    "nested/CustomDockerfile" to """
                        FROM --platform=${'$'}BUILDPLATFORM golang:1.24 AS builder
                        WORKDIR /src
                        COPY . .
                        RUN go build ./...
                        
                        FROM gcr.io/distroless/static-debian12
                        COPY --from=builder /src/app /app
                    """.trimIndent(),
                )
            ),
            githubTreeClient = DummyGithubRepositoryClient(
                mapOf(
                    "Dockerfile" to "FROM ghcr.io/navikt/baseimages/temurin:21\nADD app.jar /app/app.jar",
                    "nested/CustomDockerfile" to """
                        FROM --platform=${'$'}BUILDPLATFORM golang:1.24 AS builder
                        WORKDIR /src
                        COPY . .
                        RUN go build ./...
                        
                        FROM gcr.io/distroless/static-debian12
                        COPY --from=builder /src/app /app
                    """.trimIndent(),
                )
            ),
        )
        val webhookService = GithubWebhookService("secret", dataCollectorService, runAsync = { task -> task() })
        val body = """
            {
              "ref": "refs/heads/main",
              "after": "abcdef123456",
              "pusher": { "name": "alice" },
              "repository": {
                "id": 12345,
                "name": "demo",
                "full_name": "navikt/demo",
                "master_branch": "main"
              },
              "commits": [
                {
                  "added": ["Dockerfile", "docs/readme.md"],
                  "removed": [],
                  "modified": ["nested/CustomDockerfile", "Dockerfile"]
                },
                {
                  "added": [],
                  "removed": [],
                  "modified": ["Dockerfile"]
                }
              ]
            }
        """.trimIndent()

        val response = webhookService.handleWebhookEvent(body, generateHmac(body, "secret"))

        assertEquals("Queued 2 Dockerfile candidate(s) for processing", response)
        assertEquals(2, kafkaSender.sentMessages.size)
        assertTrue(kafkaSender.sentMessages.all { it.first == "dockerfile_features" })
        assertTrue(kafkaSender.sentMessages.any { it.second.contains("\"repoId\":\"12345\"") })
        assertTrue(kafkaSender.sentMessages.any { it.second.contains("\"path\":\"Dockerfile\"") })
        assertTrue(kafkaSender.sentMessages.any { it.second.contains("\"path\":\"nested/CustomDockerfile\"") })
        assertTrue(kafkaSender.sentMessages.any { it.second.contains("\"usesMultistage\":true") })
    }

    @Test
    fun `Should skip dockerfile candidates that are not valid dockerfiles`() {
        val kafkaSender = DummyKafkaSender()
        val dataCollectorService = DataCollectorService(
            bigQueryClient = bigQueryClient,
            kafkaSender = kafkaSender,
            githubTokenProvider = StaticGithubTokenProvider("dummy"),
            zizmorCommand = "TESTING",
            githubContentsClient = DummyGithubRepositoryClient(
                mapOf(
                    "docker/CustomDockerfile" to "\u0000PNG",
                )
            ),
            githubTreeClient = DummyGithubRepositoryClient(
                mapOf(
                    "docker/CustomDockerfile" to "\u0000PNG",
                )
            ),
        )
        val webhookService = GithubWebhookService("secret", dataCollectorService, runAsync = { task -> task() })
        val body = """
            {
              "ref": "refs/heads/main",
              "after": "abcdef123456",
              "pusher": { "name": "alice" },
              "repository": {
                "id": 12345,
                "name": "demo",
                "full_name": "navikt/demo",
                "master_branch": "main"
              },
              "commits": [
                {
                  "added": ["docker/CustomDockerfile"],
                  "removed": [],
                  "modified": []
                }
              ]
            }
        """.trimIndent()

        val response = webhookService.handleWebhookEvent(body, generateHmac(body, "secret"))

        assertEquals("Queued 1 Dockerfile candidate(s) for processing", response)
        assertTrue(kafkaSender.sentMessages.isEmpty())
    }

    @Test
    fun `Should refresh current dockerfiles when push only removes dockerfiles`() {
        val kafkaSender = DummyKafkaSender()
        val dataCollectorService = DataCollectorService(
            bigQueryClient = bigQueryClient,
            kafkaSender = kafkaSender,
            githubTokenProvider = StaticGithubTokenProvider("dummy"),
            zizmorCommand = "TESTING",
            githubContentsClient = DummyGithubRepositoryClient(
                mapOf(
                    "docker/RemainingDockerfile" to "FROM cgr.dev/chainguard/wolfi-base:latest",
                )
            ),
            githubTreeClient = DummyGithubRepositoryClient(
                mapOf(
                    "docker/RemainingDockerfile" to "FROM cgr.dev/chainguard/wolfi-base:latest",
                )
            ),
        )
        val webhookService = GithubWebhookService("secret", dataCollectorService, runAsync = { task -> task() })
        val body = """
            {
              "ref": "refs/heads/main",
              "after": "abcdef123456",
              "pusher": { "name": "alice" },
              "repository": {
                "id": 12345,
                "name": "demo",
                "full_name": "navikt/demo",
                "master_branch": "main"
              },
              "commits": [
                {
                  "added": [],
                  "removed": ["Dockerfile"],
                  "modified": []
                }
              ]
            }
        """.trimIndent()

        val response = webhookService.handleWebhookEvent(body, generateHmac(body, "secret"))

        assertEquals("Queued 1 Dockerfile candidate(s) for processing", response)
        assertEquals(1, kafkaSender.sentMessages.size)
        assertTrue(kafkaSender.sentMessages.single().second.contains("\"path\":\"docker/RemainingDockerfile\""))
    }
}
