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
        val error = assertThrows(RuntimeException::class.java) { ApplikasjonsConfig(githubOrganization = "navikt") }
        assertEquals("Missing required variable GITHUB_WEBHOOK_SECRET", error.message)
    }

    @Test
    fun `blank property webhook secrets fail configuration`() {
        listOf("", " ", "\t\r\n").forEach { secret ->
            withSecretProperty(secret) {
                val error = assertThrows(IllegalArgumentException::class.java) { ApplikasjonsConfig(githubOrganization = "navikt") }
                assertEquals("GITHUB_WEBHOOK_SECRET must not be blank", error.message)
            }
        }
    }

    @Test
    fun `blank injected webhook secrets fail configuration`() {
        listOf("", " ", "\t\r\n").forEach { secret ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                ApplikasjonsConfig(githubWebhookSecret = secret, githubOrganization = "navikt")
            }
            assertEquals("GITHUB_WEBHOOK_SECRET must not be blank", error.message)
        }
    }

    @Test
    fun `valid property webhook secret is preserved`() = withSecretProperty(" explicit-test-secret ") {
        assertEquals(" explicit-test-secret ", ApplikasjonsConfig(githubOrganization = "navikt").githubWebhookSecret)
    }

    @Test
    fun `valid injected webhook secret is preserved`() {
        val secret = " explicit-test-secret "
        assertEquals(secret, ApplikasjonsConfig(githubWebhookSecret = secret, githubOrganization = "navikt").githubWebhookSecret)
    }

    @Test
    fun `configuration errors do not expose webhook secret`() {
        val secret = "explicit-test-secret"
        val error = assertThrows(RuntimeException::class.java) {
            ApplikasjonsConfig(
                githubWebhookSecret = secret,
                githubOrganization = "navikt",
                githubAppId = "test-app",
                githubAppInstallationId = "",
                githubAppPrivateKey = "",
            )
        }
        assertFalse(error.message.orEmpty().contains(secret))
    }

    @Test
    fun `missing organization fails configuration without a fallback`() = withProperty("GITHUB_ORGANIZATION", null) {
        val error = assertThrows(RuntimeException::class.java) {
            ApplikasjonsConfig(githubWebhookSecret = "dummy")
        }
        assertEquals("Missing required variable GITHUB_ORGANIZATION", error.message)
    }

    @Test
    fun `blank property organizations fail configuration`() {
        listOf("", " ", "\t\r\n").forEach { organization ->
            withProperty("GITHUB_ORGANIZATION", organization) {
                val error = assertThrows(IllegalArgumentException::class.java) {
                    ApplikasjonsConfig(githubWebhookSecret = "dummy")
                }
                assertEquals("GITHUB_ORGANIZATION must not be blank", error.message)
            }
        }
    }

    @Test
    fun `blank injected organizations fail configuration`() {
        listOf("", " ", "\t\r\n").forEach { organization ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                ApplikasjonsConfig(githubWebhookSecret = "dummy", githubOrganization = organization)
            }
            assertEquals("GITHUB_ORGANIZATION must not be blank", error.message)
        }
    }

    @Test
    fun `configured organization is preserved`() = withProperty("GITHUB_ORGANIZATION", "another-org") {
        assertEquals("another-org", ApplikasjonsConfig(githubWebhookSecret = "dummy").githubOrganization)
        assertEquals(
            "injected-org",
            ApplikasjonsConfig(githubWebhookSecret = "dummy", githubOrganization = "injected-org").githubOrganization,
        )
    }

    private fun withSecretProperty(secret: String?, block: () -> Unit) =
        withProperty("GITHUB_WEBHOOK_SECRET", secret, block)

    private fun withProperty(name: String, value: String?, block: () -> Unit) {
        assumeTrue(System.getenv(name) == null, "Environment overrides system properties")
        val previous = System.getProperty(name)
        try {
            if (value == null) System.clearProperty(name)
            else System.setProperty(name, value)
            block()
        } finally {
            if (previous == null) System.clearProperty(name)
            else System.setProperty(name, previous)
        }
    }
}
