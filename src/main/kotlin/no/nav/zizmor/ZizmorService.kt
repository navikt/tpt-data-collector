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
        val saferRepo = repo.replace("/[^a-zA-ZÀ-Ÿ0-9-_.]/g".toRegex(), "")
        logger.info("Zizmor: running zizmor on repo: \"$org/$saferRepo\"")
        val resultString = "$zizmorCommand --quiet --cache-dir /tmp --format=json --gh-token=$githubToken $org/$saferRepo".runCommand(File("."))
        return resultString.replace("^. zizmor v.*\n".toRegex(), "")
    }
    fun analyseZizmorResult(resultString: String): ZizmorResult {
        return stringToZizmorResult(resultString)
    }
}
