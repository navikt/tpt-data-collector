package no.nav.github

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface GithubGitTreeClientInterface {
    fun listBlobPaths(owner: String, repo: String, ref: String): Set<String>
}

class GithubGitTreeClient(
    private val apiClient: GithubApiClient,
) : GithubGitTreeClientInterface {
    private val json = Json { ignoreUnknownKeys = true }

    override fun listBlobPaths(owner: String, repo: String, ref: String): Set<String> {
        validateOwnerRepoRef(owner, repo, ref, path = REPO_TREE_PATH, operation = TREE_OPERATION)
        val response = apiClient.get(
            operation = TREE_OPERATION,
            owner = owner,
            repo = repo,
            path = REPO_TREE_PATH,
            endpoint = "/repos/$owner/$repo/git/trees/${encodePathSegment(ref)}?recursive=1",
            accept = "application/vnd.github+json",
        )

        val treeResponse = try {
            json.decodeFromString<GithubTreeResponse>(response.body)
        } catch (_: Exception) {
            throw GithubRequestException(
                operation = TREE_OPERATION,
                path = REPO_TREE_PATH,
                statusCode = response.statusCode,
                kind = GithubRequestErrorKind.PERMANENT,
                message = "GitHub tree fetch failed for $owner/$repo at $ref: could not decode tree payload",
                rateLimitReset = response.rateSnapshot.reset,
            )
        }

        if (treeResponse.truncated) {
            throw GithubRequestException(
                operation = TREE_OPERATION,
                path = REPO_TREE_PATH,
                statusCode = response.statusCode,
                kind = GithubRequestErrorKind.PERMANENT,
                message = "GitHub tree fetch failed for $owner/$repo at $ref: tree was truncated and cannot be used to rebuild Dockerfile state",
                rateLimitReset = response.rateSnapshot.reset,
            )
        }

        return treeResponse.tree
            .asSequence()
            .filter { it.type == "blob" && it.path != null }
            .mapNotNull { it.path }
            .toSet()
    }

    private companion object {
        private const val TREE_OPERATION = "rest.git.trees.get.recursive"
        private const val REPO_TREE_PATH = "<repo-tree>"
    }
}

@Serializable
private data class GithubTreeResponse(
    val truncated: Boolean = false,
    val tree: List<GithubTreeEntry> = emptyList(),
)

@Serializable
private data class GithubTreeEntry(
    val path: String? = null,
    val type: String,
)
