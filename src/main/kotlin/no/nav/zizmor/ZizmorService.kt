package no.nav.zizmor

import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

fun String.runCommand(workingDir: File): String {
    val proc = ProcessBuilder(*split(" ").toTypedArray())
        .directory(workingDir)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectErrorStream(true)
        .start()

    proc.waitFor(10, TimeUnit.MINUTES)
    if (proc.exitValue() in 1..<5) {
        throw RuntimeException(
            "Exit status: ${proc.exitValue()} while running zizmor command: ${
                proc.inputStream.bufferedReader().readText()
            }"
        )
    }
    return proc.inputStream.bufferedReader().readText()
}

class ZizmorService(val githubToken: String, val zizmorCommand: String = "/app/zizmor") {
    val logger = LoggerFactory.getLogger(this::class.java)
    fun runZizmorOnRepo(org: String, repo: String): String {
        logger.info("Zizmor: running zizmor on repo: \"$org/$repo\"")
        val resultString = "$zizmorCommand --quiet --cache-dir /tmp --format=json --gh-token=$githubToken $org/$repo".runCommand(File("."))
        return resultString.replace("^. zizmor v.*\n".toRegex(), "")
    }
    fun analyseZizmorResult(repo: String, resultString: String): ZizmorResult {
        return stringToZizmorResult(repo, resultString)
    }
}
