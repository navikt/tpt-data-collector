package no.nav.whodis

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

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

    @Test
    fun `owner lookups preserve different owners for the same repository name`() = runBlocking {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            respond(
                if (request.url.encodedPath.contains("/navikt/")) """["navikt-team"]""" else """["other-team"]""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        HttpClient(engine) { install(ContentNegotiation) { json() } }.use { client ->
            val whodis = RealWhodis(client, "https://whodis.example")
            assertEquals(listOf("navikt-team"), whodis.ownerTeamsFor(nameWithOwner = "navikt/shared"))
            assertEquals(listOf("other-team"), whodis.ownerTeamsFor(nameWithOwner = "other-owner/shared"))
            assertEquals(listOf("navikt-team"), whodis.ownerTeamsFor(nameWithOwner = "navikt/a..b"))
        }
        assertEquals(
            listOf("/repository/navikt/shared/owners", "/repository/other-owner/shared/owners", "/repository/navikt/a..b/owners"),
            paths
        )
    }

    @Test
    fun `team repository listings preserve qualified names`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/nais/my-team/repositories", request.url.encodedPath)
            respond(
                """["navikt/shared","other-owner/shared"]""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        HttpClient(engine) { install(ContentNegotiation) { json() } }.use { client ->
            assertEquals(
                listOf("navikt/shared", "other-owner/shared"),
                RealWhodis(client, "https://whodis.example").repositoriesForTeam("my-team")
            )
        }
        assertEquals(listOf("navikt/fake-repo"), FakeWhodis().repositoriesForTeam("my-team"))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "", "shared", "/shared", "navikt/", "navikt/shared/extra", "navikt/..", "navikt/.", "./shared",
        "navikt/shared?query=value", "navikt/shared#fragment", "navikt/%2Fshared",
        "navikt\\other/shared", "navikt/with space", "https://other.example/shared"
    ])
    fun `invalid repository identities fail before requesting ownership`(nameWithOwner: String) {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("[]", HttpStatusCode.OK)
        }
        HttpClient(engine).use { client ->
            val whodis = RealWhodis(client, "https://whodis.example")
            assertThrows<IllegalArgumentException> {
                runBlocking { whodis.ownerTeamsFor(nameWithOwner) }
            }
        }
        assertEquals(0, requests)
    }

}