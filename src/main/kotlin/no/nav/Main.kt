package no.nav

import com.google.cloud.bigquery.DatasetId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.request.receiveText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerializationException
import no.nav.bigquery.BigQueryClient
import no.nav.bigquery.BigQueryClientInterface
import no.nav.bigquery.DummyBigQuery
import no.nav.kafka.DummyKafkaSender
import no.nav.kafka.KafkaSender
import no.nav.config.ApplikasjonsConfig
import no.nav.github.GithubWebhookService
import no.nav.metrics.metricsRoute
import no.nav.service.DataCollectorService
import org.apache.commons.codec.digest.HmacUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Calendar
import java.util.TimeZone
import java.util.Timer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.timerTask

fun main(args: Array<String>): Unit = EngineMain.main(args)

val logger: Logger = LoggerFactory.getLogger("Main")

fun Application.module(testing: Boolean = false) {
    val config = ApplikasjonsConfig()
    val datasetId = DatasetId.of(config.projectId, config.datasetName)

    val bigQueryClient: BigQueryClientInterface = if (testing)
        DummyBigQuery()
    else
        BigQueryClient(config.projectId, datasetId)

    val kafkaSender = if (testing)
        DummyKafkaSender()
    else
        KafkaSender()

    val dataCollectorService = DataCollectorService(
        bigQueryClient = bigQueryClient,
        kafkaSender = kafkaSender,
        githubToken = config.githubToken,
        zizmorCommand = if (testing) "TESTING" else "/app/zizmor",
    )
    val githubWebhookService = GithubWebhookService()

    //Start a timer to update every 24h
    Timer().scheduleAtFixedRate(timerTask {
        logger.info("Scheduled update job started")
        dataCollectorService.processDockerfileFeaturesAndSendToKafka()
        logger.info("Scheduled update job finished")
    }, calculateInitialDelayUntilClock(4, 0), TimeUnit.DAYS.toMillis(1))

    routing {
        get("/internal/isAlive") {
            if (!bigQueryClient.isAlive())
                call.respond(HttpStatusCode.ServiceUnavailable, "BigQuery is not alive")
            if (!dataCollectorService.isAlive())
                call.respond(HttpStatusCode.ServiceUnavailable, "DataCollector service is not alive")
            call.respond(HttpStatusCode.OK, "OK")
        }

        post("/webhook/github") {
            val signature = call.request.headers["X-Hub-Signature-256"] ?: call.respond(
                HttpStatusCode.Unauthorized,
                "Signature is missing"
            )
            if (
                signature.toString().length != 71 ||
                !signature.toString().startsWith("sha256=")
            ) {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }
            val body = call.receiveText()
            if (signature.toString() != generateHmac(body, config.githubWebhookSecret)) {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }

            val webhookEvent = try {
                githubWebhookService.jsonToEvent(body)
            } catch (e: SerializationException) {
                logger.warn("Failed to parse webhook event", e)
                call.respond(HttpStatusCode.BadRequest, "Bad webhook payload")
                return@post
            }

            if (githubWebhookService.shallCheckRepoWithZizmor(webhookEvent)) {
                logger.info("Running zizmor on \"${webhookEvent.payload.repository.name}\" triggered by push to \"${webhookEvent.payload.ref}\"")
                val result = dataCollectorService.checkRepoWithZizmorAndSendToKafka(webhookEvent.payload.repository.name)
                call.respond(
                    HttpStatusCode.OK, "Zizmor was run sucsessfully on: ${result.repo} " +
                            "with ${result.warnings} warnings and worst severity ${result.severity}\n"
                )
            } else {
                call.respond(
                    HttpStatusCode.OK, "Skipping zizmor on repo \"${webhookEvent.payload.repository.name}\""
                )
            }
        }

        if (!testing) {
            metricsRoute()
        }
    }
}

fun generateHmac(data: String, key: String): String {
    return "sha256=${HmacUtils("HmacSHA256", key.toByteArray()).hmacHex(data)}"
}

@Suppress("SameParameterValue")
private fun calculateInitialDelayUntilClock(hour: Int, minute: Int = 0): Long {
    val timeZone = TimeZone.getTimeZone("UTC")
    val currentTime = Calendar.getInstance(timeZone)

    val schedulerTime = Calendar.getInstance(timeZone)
    schedulerTime[Calendar.HOUR_OF_DAY] = hour
    schedulerTime[Calendar.MINUTE] = minute
    schedulerTime[Calendar.SECOND] = 0

    var initialDelay = schedulerTime.timeInMillis - currentTime.timeInMillis

    if (initialDelay < 0) {
        schedulerTime[Calendar.HOUR_OF_DAY] =
            schedulerTime[Calendar.HOUR_OF_DAY] + 24
        initialDelay =
            schedulerTime.timeInMillis - currentTime.timeInMillis
    }
    return initialDelay
}
