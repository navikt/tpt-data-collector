package no.nav

import com.google.cloud.bigquery.DatasetId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import no.nav.bigquery.BigQueryClient
import no.nav.bigquery.BigQueryClientInterface
import no.nav.bigquery.DummyBigQuery
import no.nav.config.ApplikasjonsConfig
import no.nav.github.GithubWebhookService
import no.nav.github.WebhookException
import no.nav.kafka.DummyKafkaSender
import no.nav.kafka.KafkaSender
import no.nav.metrics.metricsRoute
import no.nav.service.DataCollectorService
import org.apache.commons.codec.digest.HmacUtils
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.timerTask

fun main(args: Array<String>): Unit = EngineMain.main(args)


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
    val githubWebhookService = GithubWebhookService(config.githubWebhookSecret, dataCollectorService)

    //Start a timer to update every 24h
    Timer().scheduleAtFixedRate(timerTask {
        dataCollectorService.processDockerfileFeaturesAndSendToKafka()
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
            val signature = call.request.headers["X-Hub-Signature-256"]
            try {
                val returnMessage = githubWebhookService.handleWebhookEvent(jsonString = call.receiveText(), signature = signature)
                call.respond(HttpStatusCode.OK, returnMessage)
            } catch (e: WebhookException) {
                call.respond(e.statusCode, e.message ?: "")
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
