package no.nav.github

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource

class WebhookPayloadTests {

    @ParameterizedTest
    @ValueSource(strings = [
        "12345678-1234-1234-1234-123456789abc",
        "12345678-1234-1234-1234-123456789ABC",
    ])
    fun `valid delivery IDs are preserved`(value: String) {
        assertEquals(value, validateDeliveryId(value))
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = [
        "",
        "not-a-uuid",
        "12345678123412341234123456789abc",
        "12345678-1234-1234-1234-123456789abz",
        "12345678-1234-1234-1234-123456789abc-extra",
    ])
    fun `missing and malformed delivery IDs are omitted`(value: String?) {
        assertNull(validateDeliveryId(value))
    }

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