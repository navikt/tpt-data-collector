package no.nav.checks.files

import kotlin.time.Clock
import no.nav.checks.CheckResult


interface FileBasedCheck {
    fun filesICareAbout(allAvailableFiles: Set<String>): List<String>
    fun run(repo: String, filesToCheck: Map<String, String>): CheckResult
}

class NavBaseImageCheck : FileBasedCheck {
    private val name = this.javaClass.simpleName
    private val dockerfilePattern = Regex("""(^|[._-])[Dd]ockerfile([._-]|$)""")

    override fun filesICareAbout(allAvailableFiles: Set<String>) =
        allAvailableFiles.filter { dockerfilePattern.find(it) != null }

    override fun run(repo: String, filesToCheck: Map<String, String>): CheckResult {
        val baseImages = filesToCheck.flatMap { (_, fileContents) ->
            fileContents.lines().filter { it.startsWith("FROM") }
        }
        val now = Clock.System.now()
        return if (baseImages.last().startsWith("FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no")) {
            CheckResult.AllGood(name, now)
        } else {
            CheckResult.NeedsWork(
                name, now,
                listOf("'${baseImages.last().substringAfter("FROM ")}' is not from the Nav registry")
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
            CheckResult.NeedsWork(name, now, listOf("'COPY . .' instructions are present"))
        } else {
            CheckResult.AllGood(name, now)
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
            CheckResult.AllGood(name, now)
        } else {
            CheckResult.NeedsWork(
                name, now,
                filesToFix.map { "Repo '$repo' contains workflow '$it' with non-pinned action versions" }
            )
        }
    }
}

class PwnRequestCheck : FileBasedCheck {
    private val name = "PwnRequestCheck"
    private val workflowFilePattern = Regex("""^\.github/workflows/[A-Za-z0-9_-]+\.ya?ml$""")

    override fun filesICareAbout(allAvailableFiles: Set<String>) =
        allAvailableFiles.filter { workflowFilePattern.matches(it) }

    override fun run(repo: String, filesToCheck: Map<String, String>): CheckResult {
        val filesToFix = filesToCheck.flatMap { (filename, fileContents) ->
            fileContents.lines()
                .filter { it.contains("pull_request_target") }
                .map { filename }
                .distinct()
        }
        val now = Clock.System.now()
        return if (filesToFix.isEmpty()) {
            CheckResult.AllGood(name, now)
        } else {
            CheckResult.NeedsWork(
                name, now,
                filesToFix.map { "Repo '$repo' contains workflow '$it' with pull_request_target trigger" }
            )
        }
    }
}