package no.nav.github

import no.nav.service.DataCollectorService
import javax.swing.text.ChangedCharSetException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import io.ktor.http.HttpStatusCode
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import no.nav.data.isDockerfileCandidate
import no.nav.generateHmac

class GithubWebhookService(
    val githubWebhookSecret: String,
    val dataCollectorService: DataCollectorService,
    runAsync: ((() -> Unit) -> Unit)? = null,
) {
    val json = Json { ignoreUnknownKeys = true }
    val logger = KtorSimpleLogger(this::class.java.name)
    private val runAsync = runAsync ?: defaultRunAsync()

    fun handleWebhookEvent(jsonString: String, signature: String?): String {
        validateWebhookEvent(jsonString, signature)

        val webhookPayload = try {
            jsonToPayload(jsonString)
        } catch (e: SerializationException) {
            logger.warn("Failed to parse webhook event with SerializationException: ${e.message}", e)
            logger.debug("Event json: $jsonString")
            throw WebhookException(HttpStatusCode.BadRequest, "Bad webhook payload")
        }

        if (isRelevant(webhookPayload)) {
            logger.info("running on \"${webhookPayload.repository.name}\" triggered by push to \"${webhookPayload.ref}\"")
            val allFiles: Set<String> = webhookPayload.commits.flatMap { it.added + it.modified + it.removed }.toSet()
            val changedFiles: Set<String> = addedAndModifiedFiles(webhookPayload)
            val removedFiles: Set<String> = webhookPayload.commits.flatMap { it.removed }.toSet()
            logger.info("Changed files: $changedFiles")

            if (shouldRunZizmor(changedFiles)) {
                logger.info("MOCK: Changes in workflow-files - running Zizmor on repo ${webhookPayload.repository.name}")
                //dataCollectorService.checkRepoWithZizmorAndSendToKafka(webhookPayload.repository.name)
            }
            if (shouldUpdateDockerfiles(allFiles)) {
                logger.info("MOCK: Changes in dockerfiles - running updateDockerfiles on repo ${webhookPayload.repository.name}")
                
                val dockerfileCandidates = changedFiles.filter(::isDockerfileCandidate).toSet()
                val removedDockerfileCandidates = removedFiles.filter(::isDockerfileCandidate).toSet()
                val toggleDockerfileProcessing = true 
                if (toggleDockerfileProcessing) {
                    // TODO: Skip for now, test in prod later. We need to be sure that we don't trigger too much processing while we tune the candidate detection and processing logic.
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
            }
            return "Hello git!"
        } else {
            return "Skipping  on repo \"${webhookPayload.repository.name}\""
        }
    }
    
    private fun shouldUpdateDockerfiles(changedFiles: Set<String>): Boolean {
        return changedFiles.any { isDockerfileCandidate(it) }
    }

    private fun shouldRunZizmor(changedFiles: Set<String>): Boolean {
        return changedFiles.any { it.startsWith(".github/workflows/") }
    }
    
    private fun addedAndModifiedFiles(payload: WebhookPayload): Set<String> {
        return payload.commits
            .flatMap { it.added + it.modified }.toSet()
    }

    private fun jsonToPayload(jsonString: String): WebhookPayload {
        return json.decodeFromString<WebhookPayload>(jsonString)
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

    private fun validateWebhookEvent(jsonString: String, signature: String?){
        if (signature == null) {
            throw WebhookException(HttpStatusCode.Unauthorized, "Signature is missing")
        }
        if (
            signature.length != 71 ||
            !signature.startsWith("sha256=")
        ) {
            throw WebhookException(HttpStatusCode.Unauthorized, "Signature is bad")
        }
        if (signature != generateHmac(jsonString, githubWebhookSecret)) {
            throw WebhookException(HttpStatusCode.Unauthorized, "Signature is wrong")
        }
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

class WebhookException(val statusCode: HttpStatusCode, message: String): RuntimeException(message)
