package no.nav

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import no.nav.github.retryWithBackoff
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RetryBackoffTest {
    @Test
    fun `Retries the specified amount of times with the specified delay in between`() = runTest {
        try {
            retryWithBackoff( times = 3, initialDelay = 1.seconds, maxDelay = 5.seconds) { error("Failed") }
        } catch (_: Exception) {
            ensureActive()
        }
        assertEquals(3000L, currentTime)
    }

    @Test
    fun `Caps the delay at the max value`() = runTest {
        try {
            retryWithBackoff(times = 3, initialDelay = 3.seconds, maxDelay = 5.seconds) { error("Failed") }
        } catch (_: Exception) {
            ensureActive()
        }
        assertEquals(8000L, currentTime)
    }

    @Test
    fun `No failures = instant return`() = runTest {
        try {
            retryWithBackoff(times = 3, initialDelay = 1.seconds,maxDelay = 5.seconds) { 42 }
        } catch (_: Exception) {
            ensureActive()
            // Expected
        }
        assertEquals(0L, currentTime)
    }
}

