package no.nav.whodis

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.http.HttpHeaders.Accept
import io.ktor.http.HttpMethod.Companion.Get
import no.nav.github.requireQualifiedRepositoryName

interface Whodis {
    suspend fun ownerTeamsFor(nameWithOwner: String): List<String>
    suspend fun repositoriesForTeam(teamSlug: String): List<String>
}

open class FakeWhodis: Whodis {
    override suspend fun ownerTeamsFor(nameWithOwner: String) = listOf("tulleteam")
    override suspend fun repositoriesForTeam(teamSlug: String) = listOf("navikt/fake-repo")
}

class RealWhodis(val httpClient: HttpClient, val baseUrl: String): Whodis {
    override suspend fun ownerTeamsFor(nameWithOwner: String): List<String> {
        requireQualifiedRepositoryName(nameWithOwner)
        return httpClient.request("$baseUrl/repository/$nameWithOwner/owners") {
            method = Get
            header(Accept, "application/json")
        }.body()
    }

    override suspend fun repositoriesForTeam(teamSlug: String): List<String> =
        if (isValid(teamSlug)) {
            httpClient.request("$baseUrl/nais/$teamSlug/repositories") {
                method = Get
                header(Accept, "application/json")
            }.body()
        } else {
            emptyList()
        }

    internal fun isValid(arg: String) = arg.isNotBlank() && !arg.contains("..")

}