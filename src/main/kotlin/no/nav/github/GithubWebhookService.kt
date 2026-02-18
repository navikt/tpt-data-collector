package no.nav.github

import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class GithubWebhookService {
    val json = Json { ignoreUnknownKeys = true }
    val logger: Logger = LoggerFactory.getLogger(this::class.java)

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