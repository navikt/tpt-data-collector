package no.nav.checks.files

import kotlin.time.Clock
import no.nav.checks.CheckResult
import no.nav.checks.Severity.HIGH
import no.nav.checks.Severity.MEDIUM


interface FileBasedCheck {
    fun filesICareAbout(allAvailableFiles: Set<String>): List<String>
    fun run(repo: String, filesToCheck: Map<String, String>): CheckResult
}

class DistrolessCheck : FileBasedCheck {
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
        val nonApprovedImageUsed = approvedImages.none { lastBaseImageUsed.startsWith(it) }

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
        allAvailableFiles.filter {
            dockerfilePattern.find(it) != null || it == ".dockerignore"
        }

    override fun run(repo: String, filesToCheck: Map<String, String>): CheckResult {
        val now = Clock.System.now()

        if (filesToCheck.containsKey(".dockerignore")) {
            return CheckResult.AllGood(name, desc, severity, now)
        }

        var idxOfLastFromLine = 0
        var idxOfLastCopyLine = 0
        filesToCheck.flatMap { (filename, fileContents) ->
            fileContents.lines().map { it.lowercase() }
        }.forEachIndexed { index, line ->
            if (line.startsWith("from ")) idxOfLastFromLine = index
            if (line.startsWith("copy . .") || line.startsWith("copy ./ ./")) idxOfLastCopyLine = index
        }

        return if (idxOfLastCopyLine > idxOfLastFromLine) {
            CheckResult.NeedsWork(name, desc, severity, now, listOf("'COPY . .' instructions in the runtime image are present in $repo"))
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

class NpxUsageCheck : FileBasedCheck {
    private val name = "NpxUsageCheck"
    private val desc = "npx bypasses package-lock and may download and execute malicious versions of packages"
    private val severity = HIGH

    override fun filesICareAbout(allAvailableFiles: Set<String>) =
        allAvailableFiles.filter { it.contains("package.json") }

    override fun run(
        repo: String,
        filesToCheck: Map<String, String>
    ): CheckResult {
        val filesToFix = filesToCheck.flatMap { (filename, fileContents) ->
            fileContents.lines()
                .filter { it.contains("npx ") }
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
                filesToFix.map { "'$it' contains npx usage" }
            )
        }
    }
}

class CurlPipeShellCheck : FileBasedCheck {
    private val name = "CurlPipeShell"
    private val desc = "Excuting unknown shell scripts from the web is risky"
    private val severity = MEDIUM
    private val dockerfilePattern = Regex("""(^|[._-])[Dd]ockerfile([._-]|$)""")
    private val workflowFilePattern = Regex("""^\.github/workflows/[A-Za-z0-9_-]+\.ya?ml$""")
    private val pipeToShellPattern = Regex("""curl .*\s+|\s+(ba | z)+sh""")

    override fun filesICareAbout(allAvailableFiles: Set<String>) =
        allAvailableFiles.filter { dockerfilePattern.find(it) != null ||
                workflowFilePattern.find(it) != null ||
        it.contains("package.json")}

    override fun run(repo: String, filesToCheck: Map<String, String>): CheckResult {
        val now = Clock.System.now()
        val filesToFix = filesToCheck.flatMap { (filename, fileContents) ->
            fileContents.lines()
                .filter { pipeToShellPattern.find(it) != null }
                .map { filename }
                .distinct()
        }
        return if (filesToFix.isEmpty()) {
            CheckResult.AllGood(name, desc, severity, now)
        } else {
            CheckResult.NeedsWork(
                name,
                desc,
                severity,
                now,
                filesToFix.map { "'$it' pipes unknown scripts to the shell" }
            )
        }
    }
}

class BaseImageIsNotPinnedCheck : FileBasedCheck {
    private val name = this.javaClass.simpleName
    private val desc = "Base images should be pinned to a SHA for immutability"
    private val severity = MEDIUM
    private val dockerfilePattern = Regex("""(^|[._-])[Dd]ockerfile([._-]|$)""")

    private val chainguardImages = listOf(
        "europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no",
        "cgr.dev/chainguard",
        "chainguard/"
    )

    override fun filesICareAbout(allAvailableFiles: Set<String>) =
        allAvailableFiles.filter { dockerfilePattern.find(it) != null }

    override fun run(repo: String, filesToCheck: Map<String, String>): CheckResult {
        val nonPinnedNonChainguardImagesUsed = filesToCheck.flatMap { (_, fileContents) ->
            fileContents.lines()
                .map { it.lowercase() }
                .filter { it.startsWith("from") }
                .map { it.substringAfter("from ").substringBeforeLast("as ").trim() }
                .filterNot(::isChainguard)
        }.filterNot { it.contains("@sha") }

        val now = Clock.System.now()
        return if (nonPinnedNonChainguardImagesUsed.isEmpty()) {
            CheckResult.AllGood(name, desc, severity, now)
        } else {
            CheckResult.NeedsWork(name, desc, severity, now,
                nonPinnedNonChainguardImagesUsed.map { "'$it' is not pinned to a SHA" })
        }
    }

    private fun isChainguard(image: String) =
        chainguardImages.any { image.startsWith(it) }
}