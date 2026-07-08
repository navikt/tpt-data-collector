package no.nav.checks.repo

import no.nav.checks.AllGood
import no.nav.checks.CheckResult
import no.nav.checks.NeedsWork


interface RepoBasedCheck {
    fun filesICareAbout(allAvailableFiles: Set<String>): List<String>
    fun run(repo: String, fileName: String, fileContents: String): CheckResult
}

class ChainguardBaseImageCheck: RepoBasedCheck {
    private val name = "Chainguard base image check"
    private val dockerfilePattern = Regex("""(^|[._-])[Dd]ockerfile([._-]|$)""")

    override fun filesICareAbout(allAvailableFiles: Set<String>) =
        allAvailableFiles.filter { dockerfilePattern.find(it) != null }

    override fun run(repo: String, fileName: String, fileContents: String): CheckResult {
        val itemsToFix = fileContents.lines()
            .filter { it.startsWith("FROM") }
            .filterNot { it.startsWith("FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no") }
        return if (itemsToFix.isEmpty()) {
            AllGood(name, repo)
        }
        else {
            NeedsWork(name, repo, itemsToFix.map { "Baseimage '$it' is not from the Nav registry" })
        }
    }
}