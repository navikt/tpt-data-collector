package no.nav.checks.githubapi

import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import no.nav.checks.CheckResult
import no.nav.github.FakeGitHub
import org.junit.jupiter.api.Test

class GithubToolingStatusCheckTest {

    @Test
    fun `No analyses should be flagged`() = runTest {
        val fakeGitHub = object : FakeGitHub() {
            override suspend fun latestCodeScanningAnalysesFor(repoName: String): List<no.nav.github.GithubCodeScanningAnalysis> {
                return emptyList()
            }
        }
        val check = GithubToolingStatusCheck(fakeGitHub)
        val result = check.run("bogusrepo")
        assertTrue(result is CheckResult.NeedsWork)
    }

    @Test
    fun `Analysis with error should be flagged`() = runTest {
        val fakeGitHub = object : FakeGitHub() {
            override suspend fun latestCodeScanningAnalysesFor(repoName: String): List<no.nav.github.GithubCodeScanningAnalysis> {
                return listOf(no.nav.github.GithubCodeScanningAnalysis(
                    ".github/workflows/codeql-analysis.yml:analyse/language:perl",
                    no.nav.github.CodeScanningTool("CodeQL"),
                    "Some error",
                    kotlin.time.Clock.System.now(),
                    "https://api.github.com/repos/octocat/hello-world/code-scanning/analyses/201"
                ))
            }
        }
        val check = GithubToolingStatusCheck(fakeGitHub)
        val result = check.run("bogusrepo")
        assertTrue(result is CheckResult.NeedsWork)
    }

    @Test
    fun `Analyses with no errors should pass`() = runTest {
        // Reusing FakeGitHub is OK since it has a happy case
       val result = GithubToolingStatusCheck(FakeGitHub()).run("bogusrepo")
       assertTrue(result is CheckResult.AllGood)
    }

}