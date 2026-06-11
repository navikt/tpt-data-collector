package no.nav.local

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LocalDockerfileRunnerTest {
    @Test
    fun `fromEnv reads required and optional values`() {
        val config = LocalDockerfileRunnerConfig.fromEnv(
            mapOf(
                "TPT_DATA_COLLECTOR_GITHUB_TOKEN" to "token",
                "TPT_LOCAL_REPO_OWNER" to "navikt",
                "TPT_LOCAL_REPO_NAME" to "demo",
                "TPT_LOCAL_REPO_REF" to "abc123",
                "TPT_LOCAL_DOCKERFILE_PATH" to "docker/Dockerfile",
                "TPT_LOCAL_REPO_ID" to "123",
                "TPT_DATA_COLLECTOR_GITHUB_USER_AGENT" to "custom-agent",
            )
        )

        assertEquals("token", config.githubToken)
        assertEquals("navikt", config.owner)
        assertEquals("demo", config.repo)
        assertEquals("abc123", config.ref)
        assertEquals("docker/Dockerfile", config.dockerfilePath)
        assertEquals("123", config.repoId)
        assertEquals("custom-agent", config.githubUserAgent)
    }

    @Test
    fun `fromEnv applies defaults for optional values`() {
        val config = LocalDockerfileRunnerConfig.fromEnv(
            mapOf(
                "TPT_DATA_COLLECTOR_GITHUB_TOKEN" to "token",
                "TPT_LOCAL_REPO_OWNER" to "navikt",
                "TPT_LOCAL_REPO_NAME" to "demo",
            )
        )

        assertEquals("main", config.ref)
        assertNull(config.dockerfilePath)
        assertEquals("0", config.repoId)
        assertEquals("tpt-data-collector-local-runner", config.githubUserAgent)
    }

    @Test
    fun `fromEnv rejects missing required values`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            LocalDockerfileRunnerConfig.fromEnv(
                mapOf(
                    "TPT_LOCAL_REPO_OWNER" to "navikt",
                    "TPT_LOCAL_REPO_NAME" to "demo",
                )
            )
        }

        assertEquals("Missing required environment variable TPT_DATA_COLLECTOR_GITHUB_TOKEN", exception.message)
    }
}
