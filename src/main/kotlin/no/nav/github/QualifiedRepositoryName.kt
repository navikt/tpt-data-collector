package no.nav.github

private val qualifiedRepositoryNamePattern = Regex("[A-Za-z0-9-]+/[A-Za-z0-9_.-]+")

internal fun requireQualifiedRepositoryName(nameWithOwner: String) {
    require(qualifiedRepositoryNamePattern.matches(nameWithOwner) &&
        nameWithOwner.substringAfter('/') !in setOf(".", "..")) {
        "Repository name must be qualified as owner/name"
    }
}
