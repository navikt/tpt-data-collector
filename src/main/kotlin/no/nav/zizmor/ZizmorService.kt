package no.nav.zizmor

import io.ktor.util.logging.KtorSimpleLogger
import io.ktor.util.logging.Logger
import java.io.File
import java.util.concurrent.TimeUnit

fun String.runCommand(workingDir: File, logger: Logger): String {
    val proc = ProcessBuilder(*split(" ").toTypedArray())
        .directory(workingDir)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectErrorStream(true)
        .start()

    proc.waitFor(10, TimeUnit.MINUTES)
    if (proc.exitValue() in 1..<5) {
        val commandOutput = proc.inputStream.bufferedReader().readText()
        val httpError = commandOutput.split("\n").firstOrNull { it.contains("HTTP status") }
        if(httpError != null) {
            logger.error("Zizmor: HTTP error message: $httpError")
        }
        val message =  "Zizmor: Exit status: ${proc.exitValue()} while running zizmor command output:\n$commandOutput"
        logger.error(message)
        throw ZizmorException(message)
    }
    return proc.inputStream.bufferedReader().readText()
}

class ZizmorService(val githubToken: String, val zizmorCommand: String) {
    val logger = KtorSimpleLogger(this::class.java.name)

    fun runZizmorOnRepo(org: String, repo: String): String {
        logger.info("Zizmor: running zizmor on repo: \"$org/$repo\"")

        if (zizmorCommand == "TESTING") {
            logger.info("Zizmor: Reading zizrmor results from file. TESTING")
            return this::class.java.getResource("/zizmor_big_result.json")?.readText()
                ?: throw ZizmorException("Could not read zizmor_big_result.json")
        }

        val resultString = "$zizmorCommand --quiet --cache-dir /tmp --format=json --gh-token=$githubToken $org/$repo"
            .runCommand(File("."), logger)

        return resultString.replace("^. zizmor v.*\n".toRegex(), "")
    }

    fun analyseZizmorResult(repo: String, resultString: String): ZizmorResult {
        val zizmorResult = stringToZizmorResult(resultString)
        val filteredResult = zizmorResult
            .filterNot {
                it.ident == "unpinned-uses" && // filter out nais actions
                        it.locations.all { location -> location.feature.startsWith("nais/") }
            }
        var severity = "OK"
        filteredResult.forEach {
            if (severity != it.severity && severity.lowercase() != "high") {
                if (it.severity.lowercase() == "high" || it.severity.lowercase() == "medium") severity = it.severity
                else if (severity.lowercase() != "medium") {
                    if (it.severity.lowercase() == "low") severity = it.severity
                    else if (severity.lowercase() != "low") severity = it.severity
                }
            }
        }
        return ZizmorResult(repo = repo, severity = severity, warnings = filteredResult.size, results = filteredResult)
    }
}

class ZizmorException(message: String): RuntimeException(message)
