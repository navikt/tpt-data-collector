package no.nav.checks.files

import kotlin.time.Clock
import no.nav.checks.CheckResult


interface FileBasedCheck {
    fun filesICareAbout(allAvailableFiles: Set<String>): List<String>
    fun run(repo: String, filesToCheck: Map<String, String>): CheckResult
}

class ChainguardBaseImageCheck : FileBasedCheck {
    private val name = "ChainguardBaseImage"
    private val dockerfilePattern = Regex("""(^|[._-])[Dd]ockerfile([._-]|$)""")

    override fun filesICareAbout(allAvailableFiles: Set<String>) =
        allAvailableFiles.filter { dockerfilePattern.find(it) != null }

    override fun run(repo: String, filesToCheck: Map<String, String>): CheckResult {
        val itemsToFix = filesToCheck.flatMap { (_, fileContents) ->
            fileContents.lines()
                .filter { it.startsWith("FROM") }
                .filterNot { it.startsWith("FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no") }
        }
        val now = Clock.System.now()
        return if (itemsToFix.isEmpty()) {
            CheckResult.AllGood(name, repo, now)
        } else {
            CheckResult.NeedsWork(
                name, repo, now,
                itemsToFix.map { "Baseimage '$it' is not from the Nav registry" }
            )
        }
    }
}

class CopyDotDotCheck : FileBasedCheck {
    private val name = "CopyDotDot"
    private val dockerfilePattern = Regex("""(^|[._-])[Dd]ockerfile([._-]|$)""")

    override fun filesICareAbout(allAvailableFiles: Set<String>) =
        allAvailableFiles.filter { dockerfilePattern.find(it) != null }

    override fun run(repo: String, filesToCheck: Map<String, String>): CheckResult {
        val hasCopyDotDot = filesToCheck.flatMap { (_, fileContents) ->
            fileContents.lines()
                .map { it.trim() }
                .filter { it == "COPY . ." || it == "COPY ./ ./" }
        }.isNotEmpty()
        val now = Clock.System.now()
        return if (hasCopyDotDot) {
            CheckResult.NeedsWork(name, repo, now, listOf("'COPY . .' instructions are present"))
        } else {
            CheckResult.AllGood(name, repo, now)
        }
    }
}

class UnpinnedActionVersionsCheck : FileBasedCheck {
    private val name = "PinnedGitHubActionVersions"
    private val workflowFilePattern = Regex("""^\.github/workflows/[A-Za-z0-9_-]+\.ya?ml$""")
    private val unpinnedPattern = Regex("""^\s*-\s*uses:\s*[A-Za-z0-9_\-/]+@v.*$""")

    override fun filesICareAbout(allAvailableFiles: Set<String>) =
        allAvailableFiles.filter { workflowFilePattern.matches(it) }

    override fun run(repo: String, filesToCheck: Map<String, String>): CheckResult {
        val filesToFix = filesToCheck.flatMap { (filename, fileContents) ->
            fileContents.lines()
                .filter { unpinnedPattern.matches(it) }
                .map { filename }
                .distinct()
        }
        val now = Clock.System.now()
        return if (filesToFix.isEmpty()) {
            CheckResult.AllGood(name, repo, now)
        } else {
            CheckResult.NeedsWork(
                name, repo, now,
                filesToFix.map { "Repo '$repo' contains workflow '$it' with non-pinned action versions" }
            )
        }
    }
}