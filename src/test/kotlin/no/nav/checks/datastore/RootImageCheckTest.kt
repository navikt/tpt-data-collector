package no.nav.checks.datastore

import no.nav.checks.AllGood
import no.nav.checks.NeedsWork
import no.nav.datastore.DummyDatastore
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RootImageCheckTest {

    @Test
    fun `Stuff should not run as root in their containers`() {
        val check = RootImageCheck(DummyDatastore())
        val result = check.run("bad")
        assertTrue(result is NeedsWork)
    }

    @Test
    fun `Non-root containers rock`() {
        val check = RootImageCheck(DummyDatastore())
        val result = check.run("good")
        assertTrue(result is AllGood)
    }

}