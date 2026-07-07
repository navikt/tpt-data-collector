package no.nav.github

class DummyGithubRepositoryClient(
    private val fileContents: Map<String, String> = mapOf(
        "Dockerfile" to "FROM ghcr.io/navikt/baseimages/temurin:21\nADD app.jar /app/app.jar"
    ),
    private val analysesPerRepo: Map<String, List<CodeScanningAnalysis>> = emptyMap(),
) : GithubRepositoryContentsClientInterface, GithubGitTreeClientInterface, GithubCodeScanningClientInterface {
    override fun readFile(owner: String, repo: String, path: String, ref: String): String {
        val normalizedPath = path.trim().replace('\\', '/').removePrefix("./")
        return fileContents[normalizedPath] ?: throw GithubRequestException(
            operation = "dummy.repositories.contents.get",
            path = normalizedPath,
            statusCode = 404,
            kind = GithubRequestErrorKind.PERMANENT,
            message = "Dummy Github file not found for $normalizedPath at $ref",
        )
    }

    override fun listBlobPaths(owner: String, repo: String, ref: String): Set<String> {
        return fileContents.keys.toSet()
    }

    override fun getLatestAnalyses(owner: String, repo: String): List<CodeScanningAnalysis> {
        return analysesPerRepo[repo] ?: emptyList()
    }
}
