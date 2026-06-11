package no.nav.github

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WebhookPayloadTests {

    @Test
    fun `Should parse changed files`() {
        val body = this::class.java.getResource("/github_push_webhook.json")?.readText()!!
        val json = Json { ignoreUnknownKeys = true }
        val parsed = json.decodeFromString<WebhookPayload>(body)
        assertEquals("37d227fab89efd842a60a5c07440c72e0cf4540d", parsed.after)
        assertEquals(1137368110L, parsed.repository.id)
        assertEquals(1, parsed.commits.size)
        assertEquals("src/main/kotlin/no/nav/github/GithubWebhookService.kt", parsed.commits[0].modified[0])
    }

}