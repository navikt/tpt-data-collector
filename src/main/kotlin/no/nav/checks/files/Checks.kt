package no.nav.checks.files

import kotlin.time.Clock
import no.nav.checks.CheckResult
import no.nav.checks.Severity.HIGH
import no.nav.checks.Severity.MEDIUM


interface FileBasedCheck {
    fun filesICareAbout(allAvailableFiles: Set<String>): List<String>
    fun run(repo: String, filesToCheck: Map<String, String>): CheckResult
}

class BaseImageCheck : FileBasedCheck {
    private val name = this.javaClass.simpleName
    private val desc = "Distroless base images reduces the attack surface significantly."
    private val severity = MEDIUM
    private val dockerfilePattern = Regex("""(^|[._-])[Dd]ockerfile([._-]|$)""")

    private val approvedImages = listOf(
        "europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no",
        "cgr.dev/chainguard",
        "chainguard/",
        "gcr.io/distroless/"
    )

    override fun filesICareAbout(allAvailableFiles: Set<String>) =
        allAvailableFiles.filter { dockerfilePattern.find(it) != null }

    override fun run(repo: String, filesToCheck: Map<String, String>): CheckResult {
        val lastBaseImageUsed = filesToCheck.flatMap { (_, fileContents) ->
            fileContents.lines()
                .map { it.lowercase() }
                .filter { it.startsWith("from") }
        }.last().substringAfter("from ").substringBeforeLast("as ")
        val nonApprovedImageUsed = approvedImages.filter { lastBaseImageUsed.startsWith(it) }.isEmpty()

        val now = Clock.System.now()
        return if (nonApprovedImageUsed) {
            CheckResult.NeedsWork(name, desc,severity, now,
                listOf("'$lastBaseImageUsed' is not a recommended base image. consider switching to distroless")
            )
        } else {
            CheckResult.AllGood(name, desc, severity, now)
        }
    }
}

class CopyDotDotCheck : FileBasedCheck {
    private val name = "CopyDotDot"
    private val desc = "Distroless base images reduces the attack surface significantly."
    private val severity = MEDIUM
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
            CheckResult.NeedsWork(name, desc, severity, now, listOf("'COPY . .' instructions are present"))
        } else {
            CheckResult.AllGood(name, desc, severity, now)
        }
    }
}

class UnpinnedActionVersionsCheck : FileBasedCheck {
    private val name = "PinnedGitHubActionVersions"
    private val desc = "GitHub Action tags are not immutable, switch to using digests."
    private val severity = MEDIUM
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
            CheckResult.AllGood(name, desc, severity, now)
        } else {
            CheckResult.NeedsWork(name, desc, severity, now,
                filesToFix.map { "Workflow '$it' uses non-pinned action versions" }
            )
        }
    }
}

class PwnRequestCheck : FileBasedCheck {
    private val name = "PwnRequestCheck"
    private val desc = "'pull_request_target' triggers can lead to compromised secrets."
    private val severity = HIGH
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
            CheckResult.AllGood(name, desc, severity, now)
        } else {
            CheckResult.NeedsWork(
                name,
                desc,
                severity,
                now,
                filesToFix.map { "'$it' contains a pull_request_target trigger" }
            )
        }
    }
}