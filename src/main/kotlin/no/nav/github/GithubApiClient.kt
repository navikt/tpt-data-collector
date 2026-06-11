package no.nav.github

import io.ktor.util.logging.Logger
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class GithubApiClient internal constructor(
    private val githubToken: String,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    private val clock: Clock = Clock.systemUTC(),
    private val httpSender: HttpStringSender = JavaHttpStringSender(),
    private val sleeper: Sleeper = ThreadSleeper,
    private val coreBlockedUntil: AtomicReference<Instant> = AtomicReference(Instant.EPOCH),
) {
    fun get(
        endpoint: String,
        operation: String,
        owner: String,
        repo: String,
        path: String,
        accept: String,
        allow404: Boolean = false,
    ): GithubRestResponse {
        return executeRestCall(
            operation = operation,
            owner = owner,
            repo = repo,
            path = path,
            uri = uriFor(endpoint),
            accept = accept,
            allow404 = allow404,
        )
    }

    private fun executeRestCall(
        operation: String,
        owner: String,
        repo: String,
        path: String,
        uri: URI,
        accept: String,
        allow404: Boolean = false,
    ): GithubRestResponse {
        var transientAttempt = 1

        while (true) {
            waitForSharedCooldown()

            val request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", accept)
                .header("Authorization", "Bearer $githubToken")
                .header("User-Agent", userAgent)
                .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                .build()

            val response = try {
                httpSender.send(request)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: IOException) {
                if (transientAttempt >= MAX_TRANSIENT_ATTEMPTS) {
                    throw GithubRequestException(
                        operation = operation,
                        path = path,
                        statusCode = null,
                        kind = GithubRequestErrorKind.TRANSIENT,
                        message = "GitHub request failed for $owner/$repo:$path: transport error after $transientAttempt attempts: ${e.message}",
                    )
                }
                sleepWithInterrupt(backoffForAttempt(transientAttempt))
                transientAttempt += 1
                continue
            }

            val rateSnapshot = GithubRateSnapshot.from(response.headers())
            val statusCode = response.statusCode()
            val rateLimitWait = computeRateLimitWait(statusCode, response.headers())
            if (rateLimitWait != null) {
                publishSharedCooldown(rateLimitWait)
                transientAttempt = 1
                continue
            }

            if (allow404 && statusCode == 404) {
                return GithubRestResponse(statusCode = statusCode, body = response.body(), rateSnapshot = rateSnapshot)
            }

            if (statusCode in 500..599) {
                if (transientAttempt >= MAX_TRANSIENT_ATTEMPTS) {
                    throw GithubRequestException(
                        operation = operation,
                        path = path,
                        statusCode = statusCode,
                        kind = GithubRequestErrorKind.TRANSIENT,
                        message = "GitHub request failed for $owner/$repo:$path with status $statusCode after $transientAttempt attempts",
                        rateLimitReset = rateSnapshot.reset,
                    )
                }
                sleepWithInterrupt(backoffForAttempt(transientAttempt))
                transientAttempt += 1
                continue
            }

            if (statusCode !in 200..299) {
                throw GithubRequestException(
                    operation = operation,
                    path = path,
                    statusCode = statusCode,
                    kind = GithubRequestErrorKind.PERMANENT,
                    message = "GitHub request failed for $owner/$repo:$path with status $statusCode: ${response.body().trim().take(300)}",
                    rateLimitReset = rateSnapshot.reset,
                )
            }

            return GithubRestResponse(statusCode = statusCode, body = response.body(), rateSnapshot = rateSnapshot)
        }
    }

    private fun waitForSharedCooldown() {
        while (true) {
            val blockedUntil = coreBlockedUntil.get()
            val now = clock.instant()
            if (!blockedUntil.isAfter(now)) {
                return
            }
            sleepWithInterrupt(Duration.between(now, blockedUntil))
        }
    }

    private fun sleepWithInterrupt(duration: Duration) {
        if (duration.isZero || duration.isNegative) {
            return
        }
        try {
            sleeper.sleep(duration)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    private fun publishSharedCooldown(wait: Duration): Instant {
        val deadline = clock.instant().plus(wait)
        return coreBlockedUntil.updateAndGet { existing ->
            if (deadline.isAfter(existing)) deadline else existing
        }
    }

    private fun computeRateLimitWait(statusCode: Int, headers: HttpHeaders): Duration? {
        val retryAfter = parseRetryAfter(headers)
        if (retryAfter != null) {
            return retryAfter
        }

        val resetHeader = headers.firstValue("X-RateLimit-Reset").orElse(null)
        if (statusCode !in 200..299 && headers.firstValue("X-RateLimit-Remaining").orElse(null) == "0" && resetHeader != null) {
            val epochSeconds = resetHeader.toLongOrNull() ?: return null
            val wait = Duration.between(clock.instant(), Instant.ofEpochSecond(epochSeconds)).plusSeconds(1)
            if (!wait.isNegative && !wait.isZero) {
                return wait
            }
        }

        return null
    }

    private fun uriFor(endpoint: String): URI {
        return URI.create(apiBaseUrl.trimEnd('/') + endpoint)
    }

    companion object {
        internal const val DEFAULT_API_BASE_URL = "https://api.github.com"
        internal const val DEFAULT_USER_AGENT = "tpt-data-collector"
        private const val GITHUB_API_VERSION = "2022-11-28"
        private const val MAX_TRANSIENT_ATTEMPTS = 3
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)

        private fun parseRetryAfter(headers: HttpHeaders): Duration? {
            val retryAfter = headers.firstValue("Retry-After").orElse(null)?.toLongOrNull() ?: return null
            return if (retryAfter > 0) Duration.ofSeconds(retryAfter) else null
        }

        private fun backoffForAttempt(attempt: Int): Duration {
            return when (attempt) {
                1 -> Duration.ofSeconds(1)
                2 -> Duration.ofSeconds(2)
                else -> Duration.ofSeconds(2)
            }
        }
    }

}

internal fun validateOwnerRepoRef(owner: String, repo: String, ref: String, path: String, operation: String) {
    if (owner.isBlank()) {
        throw invalidInput(operation, path, "owner must not be blank")
    }
    if (repo.isBlank()) {
        throw invalidInput(operation, path, "repo must not be blank")
    }
    if (ref.isBlank()) {
        throw invalidInput(operation, path, "ref must not be blank")
    }
}

internal fun normalizeRepositoryPath(path: String, operation: String): String {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) {
        throw invalidInput(operation, path, "path must not be blank")
    }

    val normalizedSeparators = trimmed.replace('\\', '/')
    val rawSegments = normalizedSeparators.split(Regex("/+"))
    val segments = ArrayDeque<String>()
    rawSegments.forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> {
                if (segments.isEmpty()) {
                    throw invalidInput(operation, path, "path must stay within the repository root")
                }
                segments.removeLast()
            }

            else -> segments.addLast(segment)
        }
    }

    if (segments.isEmpty()) {
        throw invalidInput(operation, path, "path must point to a file inside the repository")
    }

    return segments.joinToString("/")
}

internal fun invalidInput(operation: String, path: String, reason: String): GithubRequestException {
    return GithubRequestException(
        operation = operation,
        path = path,
        statusCode = null,
        kind = GithubRequestErrorKind.PERMANENT,
        message = "Invalid GitHub client input for \"$path\": $reason",
    )
}

internal fun encodePathSegment(segment: String): String {
    return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")
}

internal fun encodeQueryParam(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}

internal fun interface HttpStringSender {
    @Throws(IOException::class, InterruptedException::class)
    fun send(request: HttpRequest): HttpResponse<String>
}

private class JavaHttpStringSender : HttpStringSender {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override fun send(request: HttpRequest): HttpResponse<String> {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }
}

internal fun interface Sleeper {
    @Throws(InterruptedException::class)
    fun sleep(duration: Duration)
}

private object ThreadSleeper : Sleeper {
    override fun sleep(duration: Duration) {
        Thread.sleep(duration.toMillis())
    }
}

enum class GithubRequestErrorKind {
    PRIMARY_RATE_LIMIT,
    SECONDARY_RATE_LIMIT,
    TRANSIENT,
    PERMANENT,
    CANCELLED,
}

class GithubRequestException(
    val operation: String,
    val path: String,
    val statusCode: Int?,
    val kind: GithubRequestErrorKind,
    message: String,
    val rateLimitReset: String? = null,
    val retryAfter: Duration? = null,
) : RuntimeException(message)

fun Logger.logGithubFetchFailure(repoFullName: String, exception: GithubRequestException) {
    if (exception.kind == GithubRequestErrorKind.PRIMARY_RATE_LIMIT || exception.kind == GithubRequestErrorKind.SECONDARY_RATE_LIMIT) {
        warn(
            "Failed to fetch Dockerfile candidate \"${exception.path}\" from \"$repoFullName\": ${exception.message}. " +
                "GitHub retryAfter=${exception.retryAfter?.seconds ?: "unknown"}s reset=${exception.rateLimitReset ?: "unknown"}"
        )
        return
    }

    warn("Failed to fetch Dockerfile candidate \"${exception.path}\" from \"$repoFullName\": ${exception.message}")
}

data class GithubRestResponse(
    val statusCode: Int,
    val body: String,
    val rateSnapshot: GithubRateSnapshot,
)

data class GithubRateSnapshot(
    val limit: String? = null,
    val remaining: String? = null,
    val reset: String? = null,
) {
    companion object {
        fun from(headers: HttpHeaders): GithubRateSnapshot {
            return GithubRateSnapshot(
                limit = headers.firstValue("X-RateLimit-Limit").orElse(null),
                remaining = headers.firstValue("X-RateLimit-Remaining").orElse(null),
                reset = headers.firstValue("X-RateLimit-Reset").orElse(null),
            )
        }
    }
}
