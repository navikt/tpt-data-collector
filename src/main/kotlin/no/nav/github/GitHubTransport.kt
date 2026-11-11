package no.nav.github

import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.ContentConvertException
import io.ktor.util.logging.KtorSimpleLogger
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.net.ssl.SSLException
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException

internal enum class GitHubReplay { READ, TOKEN_EXCHANGE, NEVER }

internal enum class GitHubOperation(val replay: GitHubReplay) {
    FILE_CONTENTS(GitHubReplay.READ),
    DEPENDABOT_ALERTS(GitHubReplay.READ),
    CODE_SCANNING(GitHubReplay.READ),
    REPOSITORY(GitHubReplay.READ),
    TREE(GitHubReplay.READ),
    TEAM_REPOSITORIES(GitHubReplay.READ),
    VULNERABILITY_ALERTS(GitHubReplay.READ),
    COMMITS(GitHubReplay.READ),
    // Replaying the exchange may mint another valid token; it does not invalidate the first.
    INSTALLATION_TOKEN(GitHubReplay.TOKEN_EXCHANGE)
}

internal data class GitHubTransportPolicy(
    val connectTimeout: Duration = 2.seconds,
    val requestTimeout: Duration = 5.seconds,
    val socketTimeout: Duration = 5.seconds,
    val totalTimeout: Duration = 20.seconds,
    val maxAttempts: Int = 3,
    val initialBackoff: Duration = 1.seconds,
    val maxBackoff: Duration = 5.seconds
) {
    init {
        require(maxAttempts > 0)
        require(listOf(connectTimeout, requestTimeout, socketTimeout, totalTimeout, initialBackoff, maxBackoff)
            .all { it.isPositive() && it.isFinite() && it.inWholeMilliseconds > 0 })
        require(initialBackoff <= maxBackoff)
    }
}

internal enum class GitHubFailure {
    AUTHENTICATION, AUTHORIZATION, NOT_FOUND, PRIMARY_RATE_LIMIT, SECONDARY_RATE_LIMIT,
    SERVER, HTTP, CONNECT_TIMEOUT, REQUEST_TIMEOUT, SOCKET_TIMEOUT, NETWORK, TLS,
    INVALID_RESPONSE, DEADLINE
}

internal enum class GitHubStopReason {
    PERMANENT, REPLAY_UNSAFE, ATTEMPTS_EXHAUSTED, INVALID_RATE_LIMIT_WAIT, TIME_BUDGET
}

internal class GitHubTransportException(
    val operation: GitHubOperation,
    val failure: GitHubFailure,
    val stopReason: GitHubStopReason,
    val attempt: Int,
    val response: HttpResponse? = null
) : RuntimeException(
    "GitHub operation=${operation.name} failure=${failure.name} reason=${stopReason.name} " +
        "attempt=$attempt status=${response?.status?.value ?: "none"} requestId=${response.safeRequestId()}"
)

internal class GitHubTransport(
    private val client: HttpClient,
    private val policy: GitHubTransportPolicy = GitHubTransportPolicy(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val now: () -> Instant = { Clock.System.now() },
    private val jitter: (Duration) -> Duration = { ceiling ->
        Random.nextLong(ceiling.inWholeMilliseconds / 2, ceiling.inWholeMilliseconds + 1).milliseconds
    }
) {
    private val logger = KtorSimpleLogger(GitHubTransport::class.java.name)

    inner class Attempt {
        var response: HttpResponse? = null
            private set

        fun checkGraphQLRateLimit(response: HttpResponse, errors: List<GraphQLError>) {
            // Exhausted headers alone can accompany a successful query; mixed errors must still fail closed.
            if (errors.isNotEmpty() && errors.all { it.type == "RATE_LIMITED" || it.message.isRateLimitMessage() }) {
                val primary = response.headers["X-RateLimit-Remaining"]?.trim()?.toLongOrNull() == 0L ||
                    errors.any { it.message.startsWith("API rate limit exceeded", ignoreCase = true) }
                throw GraphQLRateLimitException(response, primary)
            }
        }

        suspend fun request(method: HttpMethod, url: String, token: String, body: String? = null): HttpResponse =
            client.request(url) {
                this.method = method
                expectSuccess = true
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Accept, "application/json")
                header(HttpHeaders.UserAgent, "Nav IT McBotface")
                timeout {
                    connectTimeoutMillis = policy.connectTimeout.inWholeMilliseconds
                    requestTimeoutMillis = policy.requestTimeout.inWholeMilliseconds
                    socketTimeoutMillis = policy.socketTimeout.inWholeMilliseconds
                }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }.also {
                response = it
            }
    }

    suspend fun <T> execute(
        operation: GitHubOperation,
        context: String? = null,
        replay: GitHubReplay = operation.replay,
        block: suspend Attempt.() -> T
    ): T {
        val started = timeSource.markNow()
        var attempt = 0
        var lastResponse: HttpResponse? = null
        var activeAttempt: Attempt? = null
        val result = withTimeoutOrNull(policy.totalTimeout) {
            var backoff = policy.initialBackoff
            for (number in 1..policy.maxAttempts) {
                attempt = number
                val requestAttempt = Attempt()
                activeAttempt = requestAttempt
                val failure = try {
                    return@withTimeoutOrNull Completed(requestAttempt.block())
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: GraphQLRateLimitException) {
                    lastResponse = ex.response
                    rateLimitFailure(ex.response, ex.primary)
                } catch (ex: ResponseException) {
                    lastResponse = ex.response
                    classify(ex.response)
                } catch (_: HttpRequestTimeoutException) {
                    Failure(GitHubFailure.REQUEST_TIMEOUT, retryable = true)
                } catch (_: ConnectTimeoutException) {
                    Failure(GitHubFailure.CONNECT_TIMEOUT, retryable = true)
                } catch (_: SocketTimeoutException) {
                    Failure(GitHubFailure.SOCKET_TIMEOUT, retryable = true)
                } catch (_: SSLException) {
                    Failure(GitHubFailure.TLS)
                } catch (_: IOException) {
                    Failure(GitHubFailure.NETWORK, retryable = true)
                } catch (_: ContentConvertException) {
                    Failure(GitHubFailure.INVALID_RESPONSE)
                } catch (_: NoTransformationFoundException) {
                    Failure(GitHubFailure.INVALID_RESPONSE)
                } catch (_: SerializationException) {
                    Failure(GitHubFailure.INVALID_RESPONSE)
                }
                lastResponse = lastResponse ?: requestAttempt.response

                fun stop(reason: GitHubStopReason): Nothing {
                    diagnostic(operation, context, attempt, lastResponse, failure.kind, "stop", reason.name)
                    throw GitHubTransportException(operation, failure.kind, reason, attempt, lastResponse)
                }
                if (!failure.retryable) stop(GitHubStopReason.PERMANENT)
                if (replay == GitHubReplay.NEVER) stop(GitHubStopReason.REPLAY_UNSAFE)
                if (attempt >= policy.maxAttempts) stop(GitHubStopReason.ATTEMPTS_EXHAUSTED)
                if (failure.invalidWait) stop(GitHubStopReason.INVALID_RATE_LIMIT_WAIT)

                val jittered = jitter(backoff)
                require(jittered >= Duration.ZERO && jittered <= backoff) { "Jitter must stay within the backoff bound" }
                val wait = maxOf(jittered, failure.minimumWait)
                if (wait >= policy.totalTimeout - started.elapsedNow()) stop(GitHubStopReason.TIME_BUDGET)
                diagnostic(operation, context, attempt, lastResponse, failure.kind, "retry", "TRANSIENT", wait)
                delay(wait)
                backoff = (backoff * 2).coerceAtMost(policy.maxBackoff)
                lastResponse = null
            }
            error("Attempt budget must terminate with a result or failure")
        }
        if (result != null) return result.value
        lastResponse = lastResponse ?: activeAttempt?.response
        diagnostic(operation, context, attempt, lastResponse, GitHubFailure.DEADLINE, "stop", "TIME_BUDGET")
        throw GitHubTransportException(operation, GitHubFailure.DEADLINE, GitHubStopReason.TIME_BUDGET, attempt, lastResponse)
    }

    private suspend fun classify(response: HttpResponse): Failure {
        val status = response.status.value
        if (status == 403 || status == 429) {
            val primary = response.headers["X-RateLimit-Remaining"]?.trim()?.toLongOrNull() == 0L
            val message = response.gitHubErrorMessage()
            val limited = status == 429 || primary || response.headers[HttpHeaders.RetryAfter] != null ||
                message?.isRateLimitMessage() == true
            if (limited) {
                return rateLimitFailure(
                    response,
                    primary || message?.startsWith("API rate limit exceeded", ignoreCase = true) == true
                )
            }
        }
        return when (status) {
            401 -> Failure(GitHubFailure.AUTHENTICATION)
            403 -> Failure(GitHubFailure.AUTHORIZATION)
            404 -> Failure(GitHubFailure.NOT_FOUND)
            500, 502, 503, 504 -> {
                val header = response.headers[HttpHeaders.RetryAfter]
                val wait = header?.let(::retryAfterWait)
                Failure(
                    GitHubFailure.SERVER, retryable = true,
                    minimumWait = wait ?: Duration.ZERO, invalidWait = header != null && wait == null
                )
            }
            else -> Failure(GitHubFailure.HTTP)
        }
    }

    private fun rateLimitFailure(response: HttpResponse, primary: Boolean): Failure {
        val kind = if (primary) GitHubFailure.PRIMARY_RATE_LIMIT else GitHubFailure.SECONDARY_RATE_LIMIT
        val retryAfter = response.headers[HttpHeaders.RetryAfter]
        val reset = response.headers["X-RateLimit-Reset"]
        val waits = buildList {
            if (retryAfter != null) add(retryAfterWait(retryAfter))
            if (primary && reset != null) add(resetWait(reset))
        }
        // Without timing headers GitHub requires at least a minute before retrying a limit.
        val wait = if (waits.isEmpty()) 60.seconds else waits.filterNotNull().maxOrNull() ?: Duration.ZERO
        return Failure(kind, retryable = true, minimumWait = wait, invalidWait = waits.any { it == null })
    }

    private fun retryAfterWait(value: String): Duration? {
        val trimmed = value.trim()
        if (trimmed.matches(Regex("[0-9]+"))) {
            return trimmed.toLongOrNull()?.seconds?.takeIf { it.isFinite() }
        }
        val date = try {
            ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            return null
        }
        val target = try {
            Instant.fromEpochMilliseconds(date.toEpochMilli())
        } catch (_: ArithmeticException) {
            return null
        }
        return (target - now()).coerceAtLeast(Duration.ZERO)
    }

    private fun resetWait(value: String): Duration? =
        value.trim().toLongOrNull()?.takeIf { it >= 0 }?.seconds
            ?.minus(now().toEpochMilliseconds().milliseconds)
            ?.takeIf { it.isFinite() }
            ?.coerceAtLeast(Duration.ZERO)

    private fun diagnostic(
        operation: GitHubOperation,
        context: String?,
        attempt: Int,
        response: HttpResponse?,
        failure: GitHubFailure,
        decision: String,
        reason: String,
        wait: Duration = Duration.ZERO
    ) {
        val safeContext = context?.takeIf { it.matches(Regex("[A-Za-z0-9._/-]{1,200}")) } ?: "none"
        logger.warn(
            "GitHub transport operation=${operation.name} context=$safeContext " +
                "status=${response?.status?.value ?: "none"} requestId=${response.safeRequestId()} " +
                "attempt=$attempt failure=${failure.name} decision=$decision reason=$reason delayMs=${wait.inWholeMilliseconds}"
        )
    }

    private data class Completed<T>(val value: T)
    private class GraphQLRateLimitException(val response: HttpResponse, val primary: Boolean) :
        RuntimeException("GitHub GraphQL rate limit exceeded")

    private data class Failure(
        val kind: GitHubFailure,
        val retryable: Boolean = false,
        val minimumWait: Duration = Duration.ZERO,
        val invalidWait: Boolean = false
    )
}

private fun String.isRateLimitMessage(): Boolean =
    contains("rate limit", ignoreCase = true) || contains("abuse detection", ignoreCase = true)

private fun HttpResponse?.safeRequestId(): String =
    this?.headers?.get("X-GitHub-Request-Id")?.takeIf { it.matches(Regex("[A-Za-z0-9:-]{1,128}")) } ?: "none"
