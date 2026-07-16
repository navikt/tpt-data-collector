package no.nav.checks.datastore

import no.nav.checks.CheckResult
import no.nav.datastore.FakeDatastore
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OldDeploymentsCheckTest {

    @Test
    fun `Deployments older than 90 days are bad`() {
        val check = OldDeploymentsCheck(FakeDatastore())
        val result = check.run("bad")
        assertTrue(result is CheckResult.NeedsWork)
        println(result)
    }

    @Test
    fun `Deployments newer than 90 days are good`() {
        val check = OldDeploymentsCheck(FakeDatastore())
        val result = check.run("good")
        assertTrue(result is CheckResult.AllGood)
    }

    @Test
    fun `No deployments from repo means they can't be to old`() {
        val check = OldDeploymentsCheck(FakeDatastore())
        val result = check.run("empty")
        assertTrue(result is CheckResult.AllGood)
    }

}
