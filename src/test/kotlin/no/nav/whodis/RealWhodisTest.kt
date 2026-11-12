package no.nav.whodis

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RealWhodisTest {

    @Test
    fun `owner arrays retain all owning teams`() = runTest {
        client("""["team-a","team-b"]""").use {
            assertEquals(listOf("team-a", "team-b"), RealWhodis(it, "http://whodis").ownerTeamsFor("repo"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["[]", "null"])
    fun `successful responses without owners return an empty list`(body: String) = runTest {
        client(body).use {
            assertEquals(emptyList<String>(), RealWhodis(it, "http://whodis").ownerTeamsFor("repo"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["not-json", "{}", "\"null\"", "[null]", "[\"team-a\","])
    fun `invalid owner responses remain failures`(body: String) = runTest {
        client(body).use {
            assertThrows<JsonConvertException> { RealWhodis(it, "http://whodis").ownerTeamsFor("repo") }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403, 404, 429, 500, 503])
    fun `HTTP errors are not treated as missing owners even with a null body`(status: Int) = runTest {
        client("null", HttpStatusCode.fromValue(status)).use {
            val failure = assertThrows<ResponseException> {
                RealWhodis(it, "http://whodis").ownerTeamsFor("repo")
            }
            assertEquals(status, failure.response.status.value)
        }
    }

    private fun client(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        HttpClient(MockEngine { request ->
            assertEquals("/repository/repo/owners", request.url.encodedPath)
            respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }) {
            install(ContentNegotiation) { json() }
            expectSuccess = true
        }

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