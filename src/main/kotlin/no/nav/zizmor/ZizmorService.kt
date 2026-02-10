package no.nav.zizmor

import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

fun String.runCommand(workingDir: File): String {
    val proc = ProcessBuilder(*split(" ").toTypedArray())
        .directory(workingDir)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()

    proc.waitFor(60, TimeUnit.MINUTES)
    return proc.inputStream.bufferedReader().readText()
}

class ZizmorService(val githubToken: String) {
    val logger = LoggerFactory.getLogger(this::class.java)
    fun runZizmorOnRepo(org: String, repo: String): String {
        val saferRepo = repo.replace("/[^a-zA-ZÀ-Ÿ0-9-_.]/g".toRegex(), "")
        logger.info("Zizmor: running zizmor on repo: \"$org/$saferRepo\"")
        return "/app/zizmor --format=json --gh-token=$githubToken $org/$saferRepo 2>&1".runCommand(File("."))
    }
    fun analyseZizmorResult(resultString: String): String {
        logger.info("Zizmor: analysing result: \"$resultString\"")
        return resultString
    }
}
