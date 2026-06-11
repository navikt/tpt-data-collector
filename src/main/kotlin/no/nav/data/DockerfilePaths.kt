package no.nav.data

fun isDockerfileCandidate(path: String): Boolean {
    val fileName = path.substringAfterLast("/").lowercase()
    return fileName == "dockerfile" ||
        fileName.startsWith("dockerfile.") ||
        fileName.endsWith(".dockerfile") ||
        (!fileName.contains('.') && fileName.endsWith("dockerfile"))
}
