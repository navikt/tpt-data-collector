package no.nav.checks.files

import java.io.File
import kotlin.text.RegexOption.MULTILINE
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
    private val pipeToShellPattern = Regex("""(?i)\bcurl\b[^\n|]*\|\s*(?:/[^ \t|]+/)?z?(?:ba)?sh\b""")

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
        val namedIntermediaries = filesToCheck
            .filterNot { it.key.endsWith(".py") } // dirty trick to avoid the cartography repo
            .flatMap { (_, fileContents) ->
                fileContents.lines()
                    .map { it.lowercase().trim() }
                    .filter { it.startsWith("from") }
                    .filter { it.contains(" as ") }
                    .map { it.substringAfter(" as ").trim() }
            }

        val nonPinnedNonChainguardImages = filesToCheck
            .filterNot { it.key.endsWith(".py") } // dirty trick to avoid the cartography repo
            .flatMap { (_, fileContents) ->
            fileContents.lines()
                .map { it.lowercase().trim() }
                .filter { it.startsWith("from") }
                .map { it.substringAfter("from ").substringBeforeLast("as ").trim() }
                .filterNot(::isChainguard)
                .filterNot { namedIntermediaries.contains(it) }
        }.filterNot { it.contains("@sha") }

        val now = Clock.System.now()
        return if (nonPinnedNonChainguardImages.isEmpty()) {
            CheckResult.AllGood(name, desc, severity, now)
        } else {
            CheckResult.NeedsWork(name, desc, severity, now,
                nonPinnedNonChainguardImages.map { "'$it' is not pinned to a SHA" })
        }
    }

    private fun isChainguard(image: String) =
        chainguardImages.any { image.startsWith(it) }
}

class DependabotForAllEcosystemsCheck : FileBasedCheck {
    private val name = this.javaClass.simpleName
    private val desc = "Dependabot updates should be enabled for all ecosystems"
    private val severity = MEDIUM
    private val dockerfilePattern = Regex("""(^|[._-])[Dd]ockerfile([._-]|$)""")
    val dependabotEcosystemsPattern = "package-ecosystem:\\s+.*$".toRegex(MULTILINE)
    private val dependencyEcosystems =
        mapOf("go.mod" to "gomod",
            "pom.xml" to "maven",
            "build.gradle.kts" to "gradle",
            "package.json" to "npm",
            "dockerfile" to "docker")

    override fun filesICareAbout(allAvailableFiles: Set<String>) =
        allAvailableFiles.map { File(it) }
            .filter { it.name in dependencyEcosystems.keys || dockerfilePattern.find(it.name) != null }
            .map { it.toString() } +
                ".github/dependabot.yml"

    override fun run(repo: String, filesToCheck: Map<String, String>): CheckResult {
        val ecosystemsPresentInProject = filesToCheck.keys
            .map { File(it).name }
            .filter { it != "dependabot.yml" }
            .map { if (dockerfilePattern.find(it) != null) "dockerfile" else it }
            .mapNotNull { dependencyEcosystems[it] }

        val dependabotConfig = filesToCheck[".github/dependabot.yml"].let {
            if (it.isNullOrBlank()) null else it
        }
        val ecosystemsPresentInDependabotConfig =
            dependabotConfig?.let { dependabotConfig ->
                dependabotEcosystemsPattern.findAll(dependabotConfig)
                    .map { it.value }
                    .map { it.substringAfter("package-ecosystem:").trim() }
                    .toSet()
            } ?: emptyList()

        val ecosystemsMissingUpdates = ecosystemsPresentInProject - ecosystemsPresentInDependabotConfig.toSet()

        val now = Clock.System.now()
        if (ecosystemsMissingUpdates.isNotEmpty()) {
            return CheckResult.NeedsWork(name, desc, severity, now,
                ecosystemsMissingUpdates.map { "The '$it' ecosystem is used, but Dependabot hasn't been configured to update it's dependencies." })
        }

        return CheckResult.AllGood(name, desc, severity, now)
    }
}