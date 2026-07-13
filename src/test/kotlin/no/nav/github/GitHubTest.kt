package no.nav.github

import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test


class GitHubTest {

    @Test
    fun `token should be refreshed if expires in less than 10 mins`() {
        val now = Clock.System.now()
        val in9Minutes = now.plus(9.minutes)
        assertTrue(needsRefresh(expiresAt = in9Minutes))
    }

    @Test
    fun `token should not be refreshed if expires in more than 10 mins`() {
        val now = Clock.System.now()
        val in11Minutes = now.plus(11.minutes)
        assertFalse(needsRefresh(expiresAt = in11Minutes))
    }

    @Test
    fun `token should be refreshed if expires in less than 10 mins, different timezones`() {
        val nowInUTC = Instant.parse("2016-07-11T10:14:10Z")
        val in9MinsInSydney = Instant.parse("2016-07-11T21:23:10+11:00")
        assertTrue(needsRefresh(nowInUTC, in9MinsInSydney))
    }


    @Test
    fun `token should not be refreshed if expires in more than 10 mins, different timezones`() {
        val nowInUTC = Instant.parse("2016-07-11T10:14:10Z")
        val in11MinsInSydney = Instant.parse("2016-07-11T21:25:10+11:00")
        assertFalse(needsRefresh(nowInUTC, in11MinsInSydney))
    }

}

