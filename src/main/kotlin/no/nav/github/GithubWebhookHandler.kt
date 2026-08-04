package no.nav.github

import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.serialization.json.Json
import no.nav.checks.Checks
import no.nav.kafka.KafkaSenderInterface
import no.nav.metrics.TPTMetrics

class GithubWebhookHandler(val checks: Checks, val kafka: KafkaSenderInterface) {
    val logger = KtorSimpleLogger(this::class.java.name)

    suspend fun handle(webhookPayload: WebhookPayload) {
        TPTMetrics.webhookReceived()
        logger.info("'${webhookPayload.repository.name}' had a push to push to '${webhookPayload.ref}'")
        if (!isRelevant(webhookPayload)) {
            logger.warn("Skipping checks for '${webhookPayload.repository.name}, it is not relevant'")
            return
        }
        val changedFiles: Set<String> = webhookPayload.commits.flatMap { it.added + it.modified }.toSet()
        val results = checks.runAll(webhookPayload.repository.name, changedFiles)
        kafka.sendToKafka("CheckResult", Json.encodeToString(results))
        TPTMetrics.msgsSentToTpt(1)
    }

    private fun isRelevant(payload: WebhookPayload): Boolean {
        val pushBranch = payload.ref.split("/").last()
        return payload.repository.fullName.startsWith("navikt/") && pushBranch == payload.repository.masterBranch
    }
}
