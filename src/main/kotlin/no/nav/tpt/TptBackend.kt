package no.nav.tpt

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.util.logging.KtorSimpleLogger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.checks.CheckResultsForRepo
import no.nav.github.GitHubRepositoryMessage
import no.nav.github.GitHubSyncEvent
import no.nav.metrics.TPTMetrics
import no.nav.logging.FailureDetails
import no.nav.logging.logDetails
import no.nav.logging.logEvent
import org.slf4j.event.Level

/**
 * Delivers results to tpt-backend's callback endpoints. Implementations never throw on delivery
 * failures; they return `false` once all retries are exhausted.
 */
interface TptBackend {
    suspend fun sendVulnerabilityData(message: GitHubRepositoryMessage): Boolean
    suspend fun sendCheckResults(results: CheckResultsForRepo): Boolean
    suspend fun sendSyncStarted(event: GitHubSyncEvent): Boolean
    suspend fun sendSyncComplete(event: GitHubSyncEvent): Boolean
}

open class FakeTptBackend : TptBackend {
    val sentVulnerabilityData = mutableListOf<GitHubRepositoryMessage>()
    val sentCheckResults = mutableListOf<CheckResultsForRepo>()
    val sentSyncStarted = mutableListOf<GitHubSyncEvent>()
    val sentSyncComplete = mutableListOf<GitHubSyncEvent>()

    /** All callbacks in the order they were sent, identified by callback name. */
    val callbackOrder = mutableListOf<String>()

    override suspend fun sendVulnerabilityData(message: GitHubRepositoryMessage): Boolean {
        sentVulnerabilityData += message
        callbackOrder += VULNERABILITY_DATA
        return true
    }

    override suspend fun sendCheckResults(results: CheckResultsForRepo): Boolean {
        sentCheckResults += results
        callbackOrder += CHECK_RESULTS
        return true
    }

    override suspend fun sendSyncStarted(event: GitHubSyncEvent): Boolean {
        sentSyncStarted += event
        callbackOrder += SYNC_STARTED
        return true
    }

    override suspend fun sendSyncComplete(event: GitHubSyncEvent): Boolean {
        sentSyncComplete += event
        callbackOrder += SYNC_COMPLETE
        return true
    }
}

const val VULNERABILITY_DATA = "github_vulnerability_data"
const val CHECK_RESULTS = "check_results"
const val SYNC_STARTED = "github_sync_started"
const val SYNC_COMPLETE = "github_sync_complete"

data class RetryPolicy(
    val maxAttempts: Int = 5,
    val initialDelay: Duration = 1.seconds,
    val maxDelay: Duration = 30.seconds,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    fun delayBeforeAttempt(nextAttempt: Int): Duration =
        (initialDelay * (1 shl (nextAttempt - 2).coerceIn(0, 20))).coerceAtMost(maxDelay)
}

class TptBackendClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val tokenProvider: TokenProvider,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) : TptBackend {
    private val logger = KtorSimpleLogger(this::class.java.name)

    override suspend fun sendVulnerabilityData(message: GitHubRepositoryMessage) =
        post(VULNERABILITY_DATA, "/callbacks/github/vulnerabilities", Json.encodeToString(message),
            "repository" to message.nameWithOwner)

    override suspend fun sendCheckResults(results: CheckResultsForRepo) =
        post(CHECK_RESULTS, "/callbacks/checks", Json.encodeToString(results), "repository" to results.repoName)

    override suspend fun sendSyncStarted(event: GitHubSyncEvent) =
        post(SYNC_STARTED, "/callbacks/github/sync/started", Json.encodeToString(event),
            "teams" to event.teams.take(20).joinToString(",").take(500), "team_count" to event.teams.size)

    override suspend fun sendSyncComplete(event: GitHubSyncEvent) =
        post(SYNC_COMPLETE, "/callbacks/github/sync/complete", Json.encodeToString(event),
            "teams" to event.teams.take(20).joinToString(",").take(500), "team_count" to event.teams.size)

    private suspend fun post(
        callback: String,
        path: String,
        jsonBody: String,
        vararg context: Pair<String, Any?>
    ): Boolean {
        var attempt = 1
        while (true) {
            var dependency = "texas"
            val failure = try {
                val token = tokenProvider.token()
                dependency = "tpt_backend"
                val response = httpClient.post("${baseUrl.trimEnd('/')}$path") {
                    expectSuccess = false
                    bearerAuth(token)
                    setBody(TextContent(jsonBody, ContentType.Application.Json))
                }
                val details = FailureDetails(
                    Level.ERROR, listOf("failure" to "http", "status" to response.status.value)
                )
                when {
                    response.status.isSuccess() -> return true
                    !response.status.isRetryable() -> {
                        return giveUp(callback, context, attempt, dependency, details)
                    }
                    else -> details
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.logDetails()
            }

            if (attempt >= retryPolicy.maxAttempts) {
                return giveUp(callback, context, attempt, dependency, failure)
            }
            val backoff = retryPolicy.delayBeforeAttempt(attempt + 1)
            logger.logEvent(
                Level.WARN, "Backend callback failed; retrying",
                *context, *failure.fields.toTypedArray(),
                "callback" to callback, "dependency" to dependency,
                "attempt" to attempt, "max_attempts" to retryPolicy.maxAttempts,
                "delay_ms" to backoff.inWholeMilliseconds,
                cause = failure.cause
            )
            attempt++
            delay(backoff)
        }
    }

    private fun giveUp(
        callback: String,
        context: Array<out Pair<String, Any?>>,
        attempts: Int,
        dependency: String,
        failure: FailureDetails
    ): Boolean {
        TPTMetrics.callbackFailed(callback)
        logger.logEvent(
            Level.ERROR, "Backend callback delivery failed; update not delivered",
            *context, *failure.fields.toTypedArray(),
            "callback" to callback, "dependency" to dependency, "attempt" to attempts,
            "max_attempts" to retryPolicy.maxAttempts, cause = failure.cause
        )
        return false
    }

    private fun HttpStatusCode.isRetryable() =
        value >= 500 || this == HttpStatusCode.TooManyRequests || this == HttpStatusCode.RequestTimeout
}

fun interface TokenProvider {
    suspend fun token(): String
}

/** Fetches Entra ID machine-to-machine tokens from the Nais token endpoint (Texas), which caches them. */
class TexasTokenProvider(
    private val httpClient: HttpClient,
    private val tokenEndpoint: String,
    private val target: String,
) : TokenProvider {
    override suspend fun token(): String =
        httpClient.post(tokenEndpoint) {
            contentType(ContentType.Application.Json)
            setBody(TexasTokenRequest(identityProvider = "entra_id", target = target))
        }.body<TexasTokenResponse>().accessToken
}

@Serializable
internal data class TexasTokenRequest(
    @SerialName("identity_provider") val identityProvider: String,
    val target: String,
)

@Serializable
internal data class TexasTokenResponse(@SerialName("access_token") val accessToken: String)
