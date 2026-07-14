package no.nav.checks.files

import no.nav.checks.AllGood
import no.nav.checks.CheckResult
import no.nav.checks.NeedsWork


interface FileBasedCheck {
    fun filesICareAbout(allAvailableFiles: Set<String>): List<String>
    fun run(repo: String, filesToCheck: Map<String, String>): CheckResult
}

class ChainguardBaseImageCheck: FileBasedCheck {
    private val name = "Chainguard base image check"
    private val dockerfilePattern = Regex("""(^|[._-])[Dd]ockerfile([._-]|$)""")

    override fun filesICareAbout(allAvailableFiles: Set<String>) =
        allAvailableFiles.filter { dockerfilePattern.find(it) != null }

    override fun run(repo: String, filesToCheck: Map<String, String>): CheckResult {
        val itemsToFix = filesToCheck.flatMap{ (_, fileContents) ->
            fileContents.lines()
                .filter { it.startsWith("FROM") }
                .filterNot { it.startsWith("FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no") }
        }
        return if (itemsToFix.isEmpty()) {
            AllGood(name, repo)
        }
        else {
            NeedsWork(name, repo, itemsToFix.map { "Baseimage '$it' is not from the Nav registry" })
        }
    }
}

class CopyDotDotCheck: FileBasedCheck {
    private val name = "Uses COPY . . check"
    private val dockerfilePattern = Regex("""(^|[._-])[Dd]ockerfile([._-]|$)""")

    override fun filesICareAbout(allAvailableFiles: Set<String>) =
        allAvailableFiles.filter { dockerfilePattern.find(it) != null }

    override fun run(repo: String, filesToCheck: Map<String, String>): CheckResult {
        val hasCopyDotDot = filesToCheck.flatMap{ (_, fileContents) ->
            fileContents.lines()
                .map { it.trim() }
                .filter { it == "COPY . ." || it == "COPY ./ ./" }
        }.isNotEmpty()
        return if (hasCopyDotDot) {
            NeedsWork(name, repo, listOf("'COPY . .' instructions are present"))
        } else {
            AllGood(name, repo)
        }
    }
}

class UnpinnedActionVersionsCheck: FileBasedCheck {
    private val name = "Pinned GitHub action versions check"
    private val workflowFilePattern = Regex("""^\.github/workflows/[A-Za-z0-9_-]+\.ya?ml$""")
    private val unpinnedPattern = Regex("""^\s*-\s*uses:\s*[A-Za-z0-9_\-/]+@v.*$""")

    override fun filesICareAbout(allAvailableFiles: Set<String>) =
        allAvailableFiles.filter { workflowFilePattern.matches(it) }

    override fun run(repo: String, filesToCheck: Map<String, String>): CheckResult {
        val filesToFix = filesToCheck.flatMap{ (filename, fileContents) ->
            fileContents.lines()
                .filter { unpinnedPattern.matches(it) }
                .map { filename }
                .distinct()
        }
        return if (filesToFix.isEmpty()) {
            AllGood(name, repo)
        }
        else {
            NeedsWork(name, repo, filesToFix.map { "Repo '$repo' contains workflow '$it' with non-pinned action versions" })
        }
    }
}