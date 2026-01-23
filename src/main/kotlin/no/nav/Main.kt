package no.nav

import com.google.cloud.bigquery.DatasetId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.bigquery.BigQueryClient
import no.nav.bigquery.BigQueryClientInterface
import no.nav.bigquery.DummyBigQuery
import no.nav.kafka.DummyKafkaSender
import no.nav.kafka.KafkaSender
import no.nav.config.ApplikasjonsConfig
import no.nav.service.DataCollectorService
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

    val dataCollectorService = DataCollectorService(bigQueryClient, kafkaSender)

    //Start timer to update date every 24h
    Timer().scheduleAtFixedRate(timerTask {
        logger.info("Scheduled update job started")
        dataCollectorService.processDockerfileFeaturesAndSendToKafka()
        logger.info("Scheduled update job finished")
    }, calculateInitialDelayUntilClock(4, 0), TimeUnit.DAYS.toMillis(1))

    routing {
        get("/internal/isAlive") {
            if (!bigQueryClient.isAlive())
                call.respond(HttpStatusCode.ServiceUnavailable, "BigQuery error")
            call.respond(HttpStatusCode.OK, "OK")
        }

        get("/bigquery/dockerfile_features") {
            val rows = dataCollectorService.processDockerfileFeaturesAndSendToKafka()
            call.respond(
                HttpStatusCode.OK, "BigQuery: Number of lines sent: ${rows}\n"
            )
        }
    }
}

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
