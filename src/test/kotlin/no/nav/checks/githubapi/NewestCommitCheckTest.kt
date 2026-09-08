package no.nav.checks.githubapi

import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import no.nav.checks.CheckResult
import no.nav.github.FakeGitHub
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NewestCommitCheckTest {

    @Test
    fun `All good if time of newest commit is less than 30 days ago`() = runTest {
        val check = NewestCommitCheck(FakeGitHub())
        val results = check.run("goodOne")
        assertTrue(results is CheckResult.AllGood)
    }

    @Test
    fun `Fails if time of newest commit is more than 30 days ago`() = runTest {
        val check = NewestCommitCheck(FakeGitHub())
        val results = check.run("oldOne")
        assertTrue(results is CheckResult.NeedsWork)
        assertEquals(1, results.reasons.size)
    }
}

