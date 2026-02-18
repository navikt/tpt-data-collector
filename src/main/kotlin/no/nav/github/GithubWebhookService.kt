package no.nav.github

import io.ktor.http.HttpStatusCode
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import no.nav.generateHmac
import no.nav.service.DataCollectorService

class GithubWebhookService(val githubWebhookSecret: String, val dataCollectorService: DataCollectorService) {
    val json = Json { ignoreUnknownKeys = true }
    val logger = KtorSimpleLogger(this::class.java.name)

    fun handleWebhookEvent(jsonString: String, signature: String?): String {
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

        val webhookPayload = try {
            jsonToPayload(jsonString)
        } catch (e: SerializationException) {
            logger.warn("Zizmor: Failed to parse webhook event with SerializationException: ${e.message}", e)
            logger.debug("Event json: $jsonString")
            throw WebhookException(HttpStatusCode.BadRequest, "Bad webhook payload")
        }

        if (shallCheckRepoWithZizmor(webhookPayload)) {
            logger.info("Zizmor: running on \"${webhookPayload.repository.name}\" triggered by push to \"${webhookPayload.ref}\"")
            val result = dataCollectorService.checkRepoWithZizmorAndSendToKafka(webhookPayload.repository.name)
            val info = "Zizmor: was run sucsessfully on: ${result.repo} with ${result.warnings} warnings " +
                    "and worst severity ${result.severity}\n"
            logger.info(info)
            return info
        } else {
            logger.info("Zizmor: Skipping repo \"${webhookPayload.repository.name}\"")
            return "Skipping zizmor on repo \"${webhookPayload.repository.name}\""
        }
    }

    fun jsonToPayload(jsonString: String): WebhookPayload {
        return json.decodeFromString<WebhookPayload>(jsonString)
    }

    fun shallCheckRepoWithZizmor(payload: WebhookPayload): Boolean {
        if (!payload.repository.fullName.startsWith("navikt")) {
            logger.warn("Zizmor: Wrong org in event \"${payload.repository.fullName}\"")
            return false
        }
        val pushBranch = payload.ref.split("/").last()
        if (pushBranch != payload.repository.masterBranch) {
            logger.info("Zizmor: Push not on master branch \"${payload.ref}\" skipping check")
            return false
        }
        return true
    }
}

class WebhookException(val statusCode: HttpStatusCode, message: String): RuntimeException(message)
