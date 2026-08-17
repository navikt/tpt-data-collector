package no.nav

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.http.HttpHeaders.Accept
import io.ktor.http.HttpMethod.Companion.Get

interface Whodis {
    suspend fun ownerTeamsFor(repo: String): List<String>
}

class FakeWhodis: Whodis {
    override suspend fun ownerTeamsFor(repo: String) = listOf("tulleteam")
}

class RealWhodis(val httpClient: HttpClient): Whodis {
    val baseUrl = "http://whodis"

    override suspend fun ownerTeamsFor(repo: String): List<String> =
        httpClient.request("$baseUrl/repository/$repo/owners") {
            method = Get
            header(Accept, "application/json")
        }.body()

}