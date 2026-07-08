package no.nav.github

import io.ktor.util.logging.KtorSimpleLogger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import no.nav.data.isDockerfileCandidate
import no.nav.metrics.TPTMetrics
import no.nav.service.DataCollectorService

class GithubWebhookService(
    val dataCollectorService: DataCollectorService,
    runAsync: ((() -> Unit) -> Unit)? = null,
) {
    val logger = KtorSimpleLogger(this::class.java.name)
    private val runAsync = runAsync ?: defaultRunAsync()

    fun handleWebhookEvent(webhookPayload: WebhookPayload): String {
        if (!isRelevant(webhookPayload)) {
            return "Skipping  on repo '${webhookPayload.repository.name}'"
        }
        TPTMetrics.countWebhook()
        logger.info("running on \"${webhookPayload.repository.name}\" triggered by push to \"${webhookPayload.ref}\"")
        val allFiles: Set<String> = webhookPayload.commits.flatMap { it.added + it.modified + it.removed }.toSet()
        val changedFiles: Set<String> = addedAndModifiedFiles(webhookPayload)
        val removedFiles: Set<String> = webhookPayload.commits.flatMap { it.removed }.toSet()

        if (!shouldUpdateDockerfiles(allFiles)) {
            return "No Dockerfiles have changed, skipping"
        }
        logger.info("MOCK: Changes in dockerfiles - running updateDockerfiles on repo ${webhookPayload.repository.name}")

        val dockerfileCandidates = changedFiles.filter(::isDockerfileCandidate).toSet()
        val removedDockerfileCandidates = removedFiles.filter(::isDockerfileCandidate).toSet()
        runAsync {
            try {
                dataCollectorService.processChangedDockerfilesAndSendToKafka(
                    repoId = webhookPayload.repository.id.toString(),
                    repoFullName = webhookPayload.repository.fullName,
                    ref = webhookPayload.after,
                    candidatePaths = dockerfileCandidates,
                    removedPaths = removedDockerfileCandidates,
                )
            } catch (e: Exception) {
                logger.error("Webhook-triggered Dockerfile processing failed for \"${webhookPayload.repository.fullName}\"", e)
            }
        }
        logger.info("Queued ${(dockerfileCandidates + removedDockerfileCandidates).size} Dockerfile candidate(s) for processing")
        return ("Queued ${(dockerfileCandidates + removedDockerfileCandidates).size} Dockerfile candidate(s) for processing")
    }

    private fun shouldUpdateDockerfiles(changedFiles: Set<String>): Boolean {
        return changedFiles.any { isDockerfileCandidate(it) }
    }

    private fun addedAndModifiedFiles(payload: WebhookPayload): Set<String> {
        return payload.commits
            .flatMap { it.added + it.modified }.toSet()
    }

    private fun isRelevant(payload: WebhookPayload): Boolean {
        if (!payload.repository.fullName.startsWith("navikt/")) {
            logger.warn("Wrong org in event \"${payload.repository.fullName}\" - skipping checks")
            return false
        }
        val pushBranch = payload.ref.split("/").last()
        if (pushBranch != payload.repository.masterBranch) {
            logger.info("Push to repo \"${payload.repository.fullName}\" is not on default branch \"${payload.ref}\" - skipping checks")
            return false
        }
        return true
    }

    companion object {
        private fun defaultRunAsync(): (() -> Unit) -> Unit {
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "github-webhook-processor").apply {
                    isDaemon = false
                }
            }
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    executor.shutdown()
                    try {
                        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                            executor.shutdownNow()
                        }
                    } catch (_: InterruptedException) {
                        executor.shutdownNow()
                        Thread.currentThread().interrupt()
                    }
                },
            )

            return { task -> executor.submit(task) }
        }
    }
}
