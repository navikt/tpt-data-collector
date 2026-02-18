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

        val webhookEvent = try {
            jsonToEvent(jsonString)
        } catch (e: SerializationException) {
            logger.warn("Failed to parse webhook event with SerializationException: ${e.message}", e)
            logger.info("Event json: $jsonString")
            throw WebhookException(HttpStatusCode.BadRequest, "Bad webhook payload")
        }

        if (shallCheckRepoWithZizmor(webhookEvent)) {
            logger.info("Running zizmor on \"${webhookEvent.payload.repository.name}\" triggered by push to \"${webhookEvent.payload.ref}\"")
            val result = dataCollectorService.checkRepoWithZizmorAndSendToKafka(webhookEvent.payload.repository.name)
            return "Zizmor was run sucsessfully on: ${result.repo} with ${result.warnings} warnings " +
                    "and worst severity ${result.severity}\n"
        } else {
            return "Skipping zizmor on repo \"${webhookEvent.payload.repository.name}\""
        }
    }

    fun jsonToEvent(jsonString: String): WebhookEvent {
        return json.decodeFromString<WebhookEvent>(jsonString)
    }

    fun shallCheckRepoWithZizmor(event: WebhookEvent): Boolean {
        if (event.type != "push") {
            logger.warn("Got unknown event type \"${event.type}\"")
            return false
        }
        if (!event.payload.repository.fullName.startsWith("navikt")) {
            logger.warn("Wrong org in event \"${event.payload.repository.fullName}\"")
            return false
        }
        val pushBranch = event.payload.ref.split("/").last()
        if (pushBranch != event.payload.repository.masterBranch) {
            logger.debug("Push not on master branch \"${event.payload.ref}\"")
            return false
        }
        return true
    }
}

class WebhookException(val statusCode: HttpStatusCode, message: String): RuntimeException(message)
