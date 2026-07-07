package no.nav.github

interface GithubCodeScanningClientInterface {
    fun getLatestAnalyses(owner: String, repo: String): List<CodeScanningAnalysis>
}
