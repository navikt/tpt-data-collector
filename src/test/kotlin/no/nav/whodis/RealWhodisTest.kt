package no.nav.whodis

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RealWhodisTest {

    @Test
    fun `Params cannot have dot dot in them`() {
        val whodis = RealWhodis(HttpClient(CIO), "")
        assertFalse(whodis.isValid(".."))
        assertFalse(whodis.isValid("..."))
    }

    @Test
    fun `Params cannot be empty`() {
        val whodis = RealWhodis(HttpClient(), "")
        assertFalse(whodis.isValid("    "))
    }

    @Test
    fun `valid params are valid`() {
        val whodis = RealWhodis(HttpClient(), "")
        assertTrue(whodis.isValid("yolo"))
    }

}