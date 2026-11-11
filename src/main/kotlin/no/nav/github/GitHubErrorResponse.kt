package no.nav.github

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val errorJson = Json { ignoreUnknownKeys = true }

internal suspend fun HttpResponse.gitHubErrorMessage(): String? =
    try {
        errorJson.decodeFromString<GitHubErrorResponse>(bodyAsText()).message
    } catch (_: SerializationException) {
        null
    }

@Serializable
private data class GitHubErrorResponse(val message: String)
