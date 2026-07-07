package no.nav.local

import no.nav.bigquery.BigQueryClientInterface
import no.nav.data.isDockerfileCandidate
import no.nav.github.DummyGithubRepositoryClient
import no.nav.github.GithubApiClient
import no.nav.github.GithubGitTreeClient
import no.nav.github.GithubRepositoryContentsClient
import no.nav.github.StaticGithubTokenProvider
import no.nav.kafka.DummyKafkaSender
import no.nav.service.DataCollectorService
import kotlin.system.exitProcess

fun main() {
    val config = try {
        LocalDockerfileRunnerConfig.fromEnv(System.getenv())
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.err.println()
        System.err.println(LocalDockerfileRunnerConfig.usage())
        exitProcess(1)
    }

    val githubApiClient = GithubApiClient(
        tokenProvider = StaticGithubTokenProvider(config.githubToken),
        userAgent = config.githubUserAgent,
    )
    val githubContentsClient = GithubRepositoryContentsClient(githubApiClient)
    val githubTreeClient = GithubGitTreeClient(githubApiClient)
    val kafkaSender = DummyKafkaSender()
    val dataCollectorService = DataCollectorService(
        bigQueryClient = NoopBigQueryClient,
        kafkaSender = kafkaSender,
        githubTokenProvider = StaticGithubTokenProvider(config.githubToken),
        zizmorCommand = "TESTING",
        githubContentsClient = githubContentsClient,
        githubTreeClient = githubTreeClient,
        githubCodeScanningClient = DummyGithubRepositoryClient(),
    )

    val repoFullName = "${config.owner}/${config.repo}"
    val candidatePaths = config.dockerfilePath?.let { setOf(it) }
        ?: githubTreeClient.listBlobPaths(config.owner, config.repo, config.ref).filter(::isDockerfileCandidate).toSet()

    println("Checking $repoFullName at ${config.ref}")
    if (config.dockerfilePath == null) {
        println("Discovered ${candidatePaths.size} Dockerfile candidate(s)")
    } else {
        println("Using requested Dockerfile path: ${config.dockerfilePath}")
    }
    candidatePaths.sorted().forEach { println(" - $it") }

    if (candidatePaths.isEmpty()) {
        println()
        println("No Dockerfile candidates found.")
        return
    }

    val processedCount = dataCollectorService.processChangedDockerfilesAndSendToKafka(
        repoId = config.repoId,
        repoFullName = repoFullName,
        ref = config.ref,
        candidatePaths = candidatePaths,
    )

    println()
    println("Published $processedCount dockerfile_features message(s)")
    if (kafkaSender.sentMessages.isEmpty()) {
        println("No Kafka messages were produced.")
        return
    }

    kafkaSender.sentMessages.forEachIndexed { index, (dataType, jsonBlob) ->
        println()
        println("[$dataType ${index + 1}/${kafkaSender.sentMessages.size}]")
        println(jsonBlob)
    }
}

internal data class LocalDockerfileRunnerConfig(
    val githubToken: String,
    val owner: String,
    val repo: String,
    val ref: String,
    val dockerfilePath: String?,
    val repoId: String,
    val githubUserAgent: String,
) {
    companion object {
        fun fromEnv(env: Map<String, String>): LocalDockerfileRunnerConfig {
            return LocalDockerfileRunnerConfig(
                githubToken = required(env, "TPT_DATA_COLLECTOR_GITHUB_TOKEN"),
                owner = required(env, "TPT_LOCAL_REPO_OWNER"),
                repo = required(env, "TPT_LOCAL_REPO_NAME"),
                ref = optional(env, "TPT_LOCAL_REPO_REF") ?: "main",
                dockerfilePath = optional(env, "TPT_LOCAL_DOCKERFILE_PATH"),
                repoId = optional(env, "TPT_LOCAL_REPO_ID") ?: "0",
                githubUserAgent = optional(env, "TPT_DATA_COLLECTOR_GITHUB_USER_AGENT") ?: "tpt-data-collector-local-runner",
            )
        }

        fun usage(): String {
            return """
                Run with:
                  TPT_DATA_COLLECTOR_GITHUB_TOKEN=<token> \
                  TPT_LOCAL_REPO_OWNER=<owner> \
                  TPT_LOCAL_REPO_NAME=<repo> \
                  [TPT_LOCAL_REPO_REF=<commit-or-branch>] \
                  [TPT_LOCAL_DOCKERFILE_PATH=<path>] \
                  [TPT_LOCAL_REPO_ID=<repo-id>] \
                  ./gradlew runLocalDockerfileCheck
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

private object NoopBigQueryClient : BigQueryClientInterface {
    override fun isAlive(): Boolean = true

    override fun readTable(tableName: String): List<Map<String, String>> = emptyList()
}
