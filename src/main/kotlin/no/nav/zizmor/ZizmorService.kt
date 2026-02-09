package no.nav.zizmor

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
    fun runZizmorOnRepo(org: String, repo: String): String {
        val saferRepo = repo.replace("/[^a-zA-ZÀ-Ÿ0-9-_.]/g".toRegex(), "")
        return "/app/zizmor --quiet --format=json --gh-token=$githubToken $org/$saferRepo".runCommand(File("."))
    }
    fun analyseZizmorResult(resultString: String): String {
        return resultString
    }
}
