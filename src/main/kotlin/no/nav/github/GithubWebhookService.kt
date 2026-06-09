package no.nav.github

import io.ktor.http.HttpStatusCode
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import no.nav.generateHmac
import no.nav.service.DataCollectorService
import javax.swing.text.ChangedCharSetException

class GithubWebhookService(val githubWebhookSecret: String, val dataCollectorService: DataCollectorService) {
    val json = Json { ignoreUnknownKeys = true }
    val logger = KtorSimpleLogger(this::class.java.name)

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
            val changedFiles: Set<String> = addedAndModifiedFiles(webhookPayload)
            logger.info("Changed files: $changedFiles")

            if (shouldRunZizmor(changedFiles)) {
                logger.info("MOCK: Changes in workflow-files - running Zizmor on repo ${webhookPayload.repository.name}")
                //dataCollectorService.checkRepoWithZizmorAndSendToKafka(webhookPayload.repository.name)
            }
            if (shouldUpdateDockerfiles(changedFiles)) {
                logger.info("MOCK: Changes in dockerfiles - running updateDockerfiles on repo ${webhookPayload.repository.name}")
                //dataCollectorService.updateDockerfiles(webhookPayload.repository.name)
            }
            return "Hello git!"
        } else {
            return "Skipping  on repo \"${webhookPayload.repository.name}\""
        }
    }
    
    private fun shouldUpdateDockerfiles(changedFiles: Set<String>): Boolean {
        return changedFiles.any { it.split("/").last().contains("dockerfile", true) }
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

}

class WebhookException(val statusCode: HttpStatusCode, message: String): RuntimeException(message)
