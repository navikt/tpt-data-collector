package no.nav.logging

import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.serialization.ContentConvertException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import no.nav.github.GitHubFailure
import no.nav.github.GitHubFileInventoryException
import no.nav.github.GitHubPaginationException
import no.nav.github.GitHubTransportException
import no.nav.github.GitHubVulnerabilityCollectionException
import no.nav.github.safeRequestId
import org.apache.logging.log4j.CloseableThreadContext
import org.slf4j.Logger
import org.slf4j.event.Level

internal fun Logger.logEvent(
    level: Level,
    message: String,
    vararg fields: Pair<String, Any?>,
    cause: Throwable? = null
) {
    if (!isEnabledForLevel(level)) return
    // Scope MDC to the synchronous log call, not the coroutine's lifetime.
    CloseableThreadContext.putAll(
        fields.filter { it.second != null }.associate { (key, value) -> key to value.toString() }
    ).use {
        atLevel(level).setCause(cause).log(message)
    }
}

internal fun Logger.logFailure(
    message: String,
    failure: Throwable,
    vararg fields: Pair<String, Any?>
) {
    val details = failure.logDetails()
    logEvent(details.level, message, *fields, *details.fields.toTypedArray(), cause = details.cause)
}

internal data class FailureDetails(
    val level: Level,
    val fields: List<Pair<String, Any?>>,
    val cause: Throwable? = null
)

internal fun Throwable.logDetails(): FailureDetails = when (this) {
    is CancellationException -> throw this
    is GitHubTransportException -> FailureDetails(
        if (failure in setOf(GitHubFailure.AUTHENTICATION, GitHubFailure.AUTHORIZATION)) Level.ERROR else Level.WARN,
        listOf(
            "operation" to operation.name.lowercase(),
            "failure" to failure.name.lowercase(),
            "reason" to stopReason.name.lowercase(),
            "attempt" to attempt,
            "status" to response?.status?.value,
            "request_id" to response.safeRequestId()
        )
    )
    is GitHubVulnerabilityCollectionException -> FailureDetails(
        if (accessDenied) Level.ERROR else Level.WARN,
        listOf("failure" to "incomplete_response", "reason" to reason) + diagnostics.toList()
    )
    is GitHubFileInventoryException, is GitHubPaginationException -> FailureDetails(
        Level.WARN, listOf("failure" to "incomplete_response", "reason" to message)
    )
    is ResponseException -> FailureDetails(
        if (response.status.value in setOf(401, 403)) Level.ERROR else Level.WARN,
        listOf("failure" to "http", "status" to response.status.value)
    )
    is HttpRequestTimeoutException -> FailureDetails(
        Level.WARN, listOf("failure" to "request_timeout")
    )
    is ContentConvertException, is NoTransformationFoundException, is SerializationException -> FailureDetails(
        Level.WARN, listOf("failure" to "invalid_response", "exception_class" to javaClass.name)
    )
    is IOException -> FailureDetails(
        Level.WARN, listOf("failure" to "network", "exception_class" to javaClass.name)
    )
    else -> FailureDetails(
        Level.ERROR, listOf("failure" to "unexpected", "exception_class" to javaClass.name), this
    )
}
