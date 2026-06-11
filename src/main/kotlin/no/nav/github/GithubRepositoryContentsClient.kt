package no.nav.github

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.nio.charset.StandardCharsets
import java.util.Base64

interface GithubRepositoryContentsClientInterface {
    fun readFile(owner: String, repo: String, path: String, ref: String): String
}

class GithubRepositoryContentsClient(
    private val apiClient: GithubApiClient,
) : GithubRepositoryContentsClientInterface {
    private val json = Json { ignoreUnknownKeys = true }

    override fun readFile(owner: String, repo: String, path: String, ref: String): String {
        validateOwnerRepoRef(owner, repo, ref, path = path, operation = CONTENTS_OPERATION)
        val normalizedPath = normalizeRepositoryPath(path, CONTENTS_OPERATION)
        val encodedPath = normalizedPath
            .split("/")
            .joinToString("/") { encodePathSegment(it) }
        val response = apiClient.get(
            operation = CONTENTS_OPERATION,
            owner = owner,
            repo = repo,
            path = normalizedPath,
            endpoint = "/repos/$owner/$repo/contents/$encodedPath?ref=${encodeQueryParam(ref)}",
            accept = "application/vnd.github+json",
            allow404 = true,
        )

        if (response.statusCode == 404) {
            throw GithubRequestException(
                operation = CONTENTS_OPERATION,
                path = normalizedPath,
                statusCode = 404,
                kind = GithubRequestErrorKind.PERMANENT,
                message = "GitHub file fetch failed for $owner/$repo:$normalizedPath at $ref with status 404",
                rateLimitReset = response.rateSnapshot.reset,
            )
        }

        val element = try {
            json.parseToJsonElement(response.body)
        } catch (_: Exception) {
            throw GithubRequestException(
                operation = CONTENTS_OPERATION,
                path = normalizedPath,
                statusCode = response.statusCode,
                kind = GithubRequestErrorKind.PERMANENT,
                message = "GitHub file fetch failed for $owner/$repo:$normalizedPath at $ref: invalid JSON response",
                rateLimitReset = response.rateSnapshot.reset,
            )
        }

        if (element is JsonArray) {
            throw GithubRequestException(
                operation = CONTENTS_OPERATION,
                path = normalizedPath,
                statusCode = response.statusCode,
                kind = GithubRequestErrorKind.PERMANENT,
                message = "GitHub file fetch failed for $owner/$repo:$normalizedPath at $ref: path resolves to a directory, not a file",
                rateLimitReset = response.rateSnapshot.reset,
            )
        }
        if (element !is JsonObject) {
            throw GithubRequestException(
                operation = CONTENTS_OPERATION,
                path = normalizedPath,
                statusCode = response.statusCode,
                kind = GithubRequestErrorKind.PERMANENT,
                message = "GitHub file fetch failed for $owner/$repo:$normalizedPath at $ref: unexpected JSON payload",
                rateLimitReset = response.rateSnapshot.reset,
            )
        }

        val fileResponse = try {
            json.decodeFromJsonElement<GithubContentsResponse>(element)
        } catch (_: Exception) {
            throw GithubRequestException(
                operation = CONTENTS_OPERATION,
                path = normalizedPath,
                statusCode = response.statusCode,
                kind = GithubRequestErrorKind.PERMANENT,
                message = "GitHub file fetch failed for $owner/$repo:$normalizedPath at $ref: could not decode contents payload",
                rateLimitReset = response.rateSnapshot.reset,
            )
        }

        if (fileResponse.type != "file") {
            throw GithubRequestException(
                operation = CONTENTS_OPERATION,
                path = normalizedPath,
                statusCode = response.statusCode,
                kind = GithubRequestErrorKind.PERMANENT,
                message = "GitHub file fetch failed for $owner/$repo:$normalizedPath at $ref: path is a ${fileResponse.type}, not a file",
                rateLimitReset = response.rateSnapshot.reset,
            )
        }

        if (fileResponse.encoding != "base64" || fileResponse.content == null) {
            throw GithubRequestException(
                operation = CONTENTS_OPERATION,
                path = normalizedPath,
                statusCode = response.statusCode,
                kind = GithubRequestErrorKind.PERMANENT,
                message = "GitHub file fetch failed for $owner/$repo:$normalizedPath at $ref: missing or unsupported content encoding ${fileResponse.encoding ?: "unknown"}",
                rateLimitReset = response.rateSnapshot.reset,
            )
        }

        val decodedBytes = try {
            Base64.getMimeDecoder().decode(fileResponse.content)
        } catch (_: IllegalArgumentException) {
            throw GithubRequestException(
                operation = CONTENTS_OPERATION,
                path = normalizedPath,
                statusCode = response.statusCode,
                kind = GithubRequestErrorKind.PERMANENT,
                message = "GitHub file fetch failed for $owner/$repo:$normalizedPath at $ref: could not decode base64 content",
                rateLimitReset = response.rateSnapshot.reset,
            )
        }

        return decodedBytes.toString(StandardCharsets.UTF_8)
    }

    private companion object {
        private const val CONTENTS_OPERATION = "rest.repositories.contents.get"
    }
}

@Serializable
private data class GithubContentsResponse(
    val type: String,
    val encoding: String? = null,
    val content: String? = null,
)
