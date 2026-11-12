package no.nav.checks.datastore

import no.nav.checks.CheckResult
import no.nav.datastore.FakeDatastore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OldDeploymentsCheckTest {

    @Test
    fun `Deployments older than 90 days are bad`() {
        val check = OldDeploymentsCheck(FakeDatastore())
        val result = assertInstanceOf(CheckResult.NeedsWork::class.java, check.run("navikt/bad"))
        assertEquals(1, result.reasons.size)
        assertTrue(result.reasons.single().contains("deployment of navikt/bad in env2"))
    }

    @Test
    fun `Deployments newer than 90 days are good`() {
        val check = OldDeploymentsCheck(FakeDatastore())
        val result = check.run("navikt/good")
        assertTrue(result is CheckResult.AllGood)
    }

    @Test
    fun `No deployments from repo means they can't be to old`() {
        val check = OldDeploymentsCheck(FakeDatastore())
        val result = check.run("navikt/empty")
        assertTrue(result is CheckResult.AllGood)
    }

    @Test
    fun `Repositories with the same short name under different owners remain distinct`() {
        val check = OldDeploymentsCheck(FakeDatastore())
        assertTrue(check.run("navikt/bad") is CheckResult.NeedsWork)
        assertTrue(check.run("another-owner/bad") is CheckResult.AllGood)
    }

}
