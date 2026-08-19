package no.nav

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.http.HttpHeaders.Accept
import io.ktor.http.HttpMethod.Companion.Get

interface Whodis {
    suspend fun ownerTeamsFor(repo: String): List<String>
    suspend fun repositoriesForTeam(teamSlug: String): List<String>
}

open class FakeWhodis: Whodis {
    override suspend fun ownerTeamsFor(repo: String) = listOf("tulleteam")
    override suspend fun repositoriesForTeam(teamSlug: String) = listOf("navikt/fake-repo")
}

class RealWhodis(val httpClient: HttpClient, val baseUrl: String): Whodis {

    override suspend fun ownerTeamsFor(repo: String): List<String> =
        httpClient.request("$baseUrl/repository/$repo/owners") {
            method = Get
            header(Accept, "application/json")
        }.body()

    override suspend fun repositoriesForTeam(teamSlug: String): List<String> =
        httpClient.request("$baseUrl/nais/$teamSlug/repositories") {
            method = Get
            header(Accept, "application/json")
        }.body()

}