package no.nav.github

import io.ktor.util.logging.KtorSimpleLogger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

val logger = KtorSimpleLogger("retryWithBackoff")

suspend fun <T> retryWithBackoff(
    times: Int = 3,
    initialDelay: Duration = 1.seconds,
    maxDelay: Duration = 5.seconds,
    factor: Int = 2,
    block: suspend () -> T
): T {
    var currentDelay = initialDelay
    repeat(times - 1) {
        try {
            return block()
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.warn("Failed, retrying in $currentDelay", ex)
        }

        delay(currentDelay)
        currentDelay = (currentDelay * factor).coerceAtMost(maxDelay)
    }
    return block()
}