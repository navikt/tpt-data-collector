package no.nav.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ApplikasjonsConfigTest {
    @Test
    fun `missing webhook secret fails configuration`() = withSecretProperty(null) {
        val error = assertThrows(RuntimeException::class.java) { ApplikasjonsConfig() }
        assertEquals("Missing required variable GITHUB_WEBHOOK_SECRET", error.message)
    }

    @Test
    fun `blank property webhook secrets fail configuration`() {
        listOf("", " ", "\t\r\n").forEach { secret ->
            withSecretProperty(secret) {
                val error = assertThrows(IllegalArgumentException::class.java) { ApplikasjonsConfig() }
                assertEquals("GITHUB_WEBHOOK_SECRET must not be blank", error.message)
            }
        }
    }

    @Test
    fun `blank injected webhook secrets fail configuration`() {
        listOf("", " ", "\t\r\n").forEach { secret ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                ApplikasjonsConfig(githubWebhookSecret = secret)
            }
            assertEquals("GITHUB_WEBHOOK_SECRET must not be blank", error.message)
        }
    }

    @Test
    fun `valid property webhook secret is preserved`() = withSecretProperty(" explicit-test-secret ") {
        assertEquals(" explicit-test-secret ", ApplikasjonsConfig().githubWebhookSecret)
    }

    @Test
    fun `valid injected webhook secret is preserved`() {
        val secret = " explicit-test-secret "
        assertEquals(secret, ApplikasjonsConfig(githubWebhookSecret = secret).githubWebhookSecret)
    }

    @Test
    fun `configuration errors do not expose webhook secret`() {
        val secret = "explicit-test-secret"
        val error = assertThrows(RuntimeException::class.java) {
            ApplikasjonsConfig(
                githubWebhookSecret = secret,
                githubAppId = "test-app",
                githubAppInstallationId = "",
                githubAppPrivateKey = "",
            )
        }
        assertFalse(error.message.orEmpty().contains(secret))
    }

    private fun withSecretProperty(secret: String?, block: () -> Unit) {
        assumeTrue(System.getenv("GITHUB_WEBHOOK_SECRET") == null, "Environment overrides system properties")
        val previous = System.getProperty("GITHUB_WEBHOOK_SECRET")
        try {
            if (secret == null) System.clearProperty("GITHUB_WEBHOOK_SECRET")
            else System.setProperty("GITHUB_WEBHOOK_SECRET", secret)
            block()
        } finally {
            if (previous == null) System.clearProperty("GITHUB_WEBHOOK_SECRET")
            else System.setProperty("GITHUB_WEBHOOK_SECRET", previous)
        }
    }
}
