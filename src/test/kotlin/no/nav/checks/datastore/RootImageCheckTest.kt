package no.nav.checks.datastore

import no.nav.checks.CheckResult
import no.nav.datastore.FakeDatastore
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RootImageCheckTest {

    @Test
    fun `Stuff should not run as root in their containers`() {
        val check = RootImageCheck(FakeDatastore())
        val result = check.run("bad")
        assertTrue(result is CheckResult.NeedsWork)
    }

    @Test
    fun `Non-root containers rock`() {
        val check = RootImageCheck(FakeDatastore())
        val result = check.run("good")
        assertTrue(result is CheckResult.AllGood)
    }

}