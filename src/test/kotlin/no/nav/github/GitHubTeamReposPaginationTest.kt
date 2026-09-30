package no.nav.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.io.StringWriter
import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GitHubTeamReposPaginationTest {
    private val base = "https://api.github.com/orgs/navikt/teams/big-team/repos"

    private fun privateKeyPem(): String {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        return StringWriter().also { writer -> JcaPEMWriter(writer).use { it.writeObject(keyPair) } }.toString()
    }

    private fun reposJson(from: Int, to: Int, archived: Set<Int> = emptySet()) =
        (from..to).joinToString(",", "[", "]") { """{"name":"repo-$it","archived":${it in archived}}""" }

    @Test
    fun `fetches every page of a team's repositories`() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        val engine = MockEngine { request ->
            val url = request.url.toString()
            if (request.url.encodedPath.endsWith("/access_tokens")) {
                return@MockEngine respond("""{"token":"t","expires_at":"2099-01-01T00:00:00Z"}""", HttpStatusCode.Created, json)
            }
            requestedUrls += url
            when (request.url.parameters["page"]) {
                null -> respond(
                    reposJson(1, 100, archived = setOf(7)), HttpStatusCode.OK,
                    headersOf(
                        HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                        HttpHeaders.Link to listOf("<$base?per_page=100&page=2>; rel=\"next\", <$base?per_page=100&page=3>; rel=\"last\"")
                    )
                )
                "2" -> respond(
                    reposJson(101, 200), HttpStatusCode.OK,
                    headersOf(
                        HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                        HttpHeaders.Link to listOf("<$base?per_page=100&page=1>; rel=\"prev\", <$base?per_page=100&page=3>; rel=\"next\"")
                    )
                )
                else -> respond(
                    reposJson(201, 230), HttpStatusCode.OK,
                    headersOf(
                        HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                        HttpHeaders.Link to listOf("<$base?per_page=100&page=2>; rel=\"prev\", <$base?per_page=100&page=1>; rel=\"first\"")
                    )
                )
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = true
        }
        val gitHub = RealGitHub(httpClient, "123", "456", privateKeyPem())

        val repos = gitHub.allReposForTeam("big-team")

        assertEquals(229, repos.size)
        assertEquals((1..230).filter { it != 7 }.map { "repo-$it" }, repos)
        assertEquals(
            listOf("$base?per_page=100", "$base?per_page=100&page=2", "$base?per_page=100&page=3"),
            requestedUrls
        )
    }

    @Test
    fun `finds the next page url in a link header`() {
        assertEquals(
            "https://api.github.com/x?page=2",
            nextPageUrl("<https://api.github.com/x?page=1>; rel=\"prev\", <https://api.github.com/x?page=2>; rel=\"next\"")
        )
        assertNull(nextPageUrl("<https://api.github.com/x?page=1>; rel=\"first\""))
        assertNull(nextPageUrl(null))
    }
}
