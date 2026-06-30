package no.nav.local

import no.nav.github.GithubApiClient
import no.nav.github.GithubCodeScanningClient
import no.nav.github.StaticGithubTokenProvider
import no.nav.data.CodeScanningToolStatus
import kotlin.system.exitProcess

fun main() {
    val config = try {
        LocalCodeScanningRunnerConfig.fromEnv(System.getenv())
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.err.println()
        System.err.println(LocalCodeScanningRunnerConfig.usage())
        exitProcess(1)
    }

    val githubApiClient = GithubApiClient(
        tokenProvider = StaticGithubTokenProvider(config.githubToken),
        userAgent = config.githubUserAgent,
    )
    val codeScanningClient = GithubCodeScanningClient(githubApiClient)

    println("Fetching code scanning analyses for ${config.owner}/${config.repo} ...")
    val analyses = codeScanningClient.getLatestAnalyses(config.owner, config.repo)

    if (analyses.isEmpty()) {
        println("No analyses found — repo has no code scanning configured (or token lacks security_events scope).")
        return
    }

    println("Got ${analyses.size} analysis record(s). Tools found: ${analyses.map { it.tool.name }.distinct().joinToString()}")
    println()

    val status = CodeScanningToolStatus.from(
        repoId = config.repoId,
        repoName = "${config.owner}/${config.repo}",
        collectedAt = java.time.Instant.now().toString(),
        analyses = analyses,
    )

    println(status.toJson())
}

internal data class LocalCodeScanningRunnerConfig(
    val githubToken: String,
    val owner: String,
    val repo: String,
    val repoId: String,
    val githubUserAgent: String,
) {
    companion object {
        fun fromEnv(env: Map<String, String>): LocalCodeScanningRunnerConfig {
            return LocalCodeScanningRunnerConfig(
                githubToken = required(env, "TPT_DATA_COLLECTOR_GITHUB_TOKEN"),
                owner = optional(env, "TPT_LOCAL_REPO_OWNER") ?: "navikt",
                repo = required(env, "TPT_LOCAL_REPO_NAME"),
                repoId = optional(env, "TPT_LOCAL_REPO_ID") ?: "0",
                githubUserAgent = optional(env, "TPT_DATA_COLLECTOR_GITHUB_USER_AGENT") ?: "tpt-data-collector-local-runner",
            )
        }

        fun usage(): String {
            return """
                Run with:
                  TPT_DATA_COLLECTOR_GITHUB_TOKEN=<token> \
                  TPT_LOCAL_REPO_NAME=<repo> \
                  [TPT_LOCAL_REPO_OWNER=<owner, default: navikt>] \
                  [TPT_LOCAL_REPO_ID=<repo-id>] \
                  ./gradlew runLocalCodeScanningCheck
            """.trimIndent()
        }

        private fun required(env: Map<String, String>, key: String): String {
            return optional(env, key) ?: throw IllegalArgumentException("Missing required environment variable $key")
        }

        private fun optional(env: Map<String, String>, key: String): String? {
            return env[key]?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}
