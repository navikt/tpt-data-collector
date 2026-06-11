package no.nav.data

class DockerfileFeatureExtractor {
    fun extract(
        repoId: String,
        repoName: String,
        path: String,
        content: String,
        whenCollected: kotlinx.datetime.LocalDateTime?,
        fileType: String = "dockerfile",
    ): DockerfileFeature? {
        if (!isTextContent(content)) {
            return null
        }

        val fromLines = content.lineSequence()
            .map { it.trimStart() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .filter { FROM_REGEX.containsMatchIn(it) }
            .toList()

        if (fromLines.isEmpty()) {
            return null
        }

        val baseImageLine = fromLines.last()
        val baseImage = extractBaseImage(baseImageLine)
        val pinsBaseImage = baseImageLine.contains("@sha256:")
        val usesChainguard = isChainguard(baseImage)
        val usesDistroless = usesDistroless(baseImage, usesChainguard)

        return DockerfileFeature(
            repoId = repoId,
            repoName = repoName,
            path = path,
            whenCollected = whenCollected,
            fileType = fileType,
            baseImage = baseImage,
            pinsBaseImage = pinsBaseImage,
            usesMultistage = fromLines.size > 1,
            usesChainguard = usesChainguard,
            usesDistroless = usesDistroless,
        )
    }

    private fun extractBaseImage(fromLine: String): String {
        val tokens = fromLine
            .replaceFirst(FROM_PREFIX_REGEX, "")
            .split(WHITESPACE_REGEX)
            .filter { it.isNotBlank() }

        return tokens
            .dropWhile { it.startsWith("--") }
            .firstOrNull()
            ?.replace("@sha256:.*".toRegex(), "")
            .orEmpty()
    }

    private fun isChainguard(baseImage: String): Boolean {
        val containsPullThough = baseImage.startsWith("europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/")
        val containsDirectChainguard = baseImage.startsWith("cgr.dev/chainguard/")
        return containsPullThough || containsDirectChainguard
    }

    private fun usesDistroless(baseImage: String, usesChainguard: Boolean): Boolean {
        if (!usesChainguard) {
            return baseImage.contains("/distroless/")
        }

        if (baseImage.endsWith("-dev")) {
            return false
        }

        val isNode = baseImage.contains("/node:")
        val isNodeSlim = baseImage.contains("node:.*-slim$".toRegex())
        return !isNode || isNodeSlim
    }

    private fun isTextContent(content: String): Boolean = !content.contains('\u0000')

    companion object {
        private val FROM_REGEX = Regex("""^FROM\b.*""", RegexOption.IGNORE_CASE)
        private val FROM_PREFIX_REGEX = Regex("""^FROM\s+""", RegexOption.IGNORE_CASE)
        private val WHITESPACE_REGEX = Regex("""\s+""")
    }
}
