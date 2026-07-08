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
import java.util.Calendar
import java.util.TimeZone
import java.util.Timer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.timerTask
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import no.nav.bigquery.BigQueryClient
import no.nav.bigquery.BigQueryClientInterface
import no.nav.bigquery.DummyBigQuery
import no.nav.config.ApplikasjonsConfig
import no.nav.datastore.Datastore
import no.nav.datastore.DummyDatastore
import no.nav.datastore.Neo4jDatastore
import no.nav.github.DummyGithubRepositoryClient
import no.nav.github.GithubApiClient
import no.nav.github.GithubAppAuth
import no.nav.github.GithubGitTreeClient
import no.nav.github.GithubRepositoryContentsClient
import no.nav.github.GithubTokenProvider
import no.nav.github.GithubWebhookHandler
import no.nav.github.StaticGithubTokenProvider
import no.nav.github.WebhookPayload
import no.nav.kafka.DummyKafkaSender
import no.nav.kafka.KafkaSender
import no.nav.metrics.metricsRoute
import no.nav.service.DataCollectorService
import org.apache.commons.codec.digest.HmacUtils
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase

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

    val githubTokenProvider = if (testing) {
        StaticGithubTokenProvider("dummy")
    } else {
        createGithubTokenProvider(config)
    }
    val githubRepositoryClient = if (testing) DummyGithubRepositoryClient() else null
    val githubApiClient = if (testing) null else GithubApiClient(
        tokenProvider = githubTokenProvider,
        userAgent = config.githubUserAgent,
    )
    val githubContentsClient = if (testing) {
        githubRepositoryClient!!
    } else {
        GithubRepositoryContentsClient(githubApiClient!!)
    }
    val githubTreeClient = if (testing) {
        githubRepositoryClient!!
    } else {
        GithubGitTreeClient(githubApiClient!!)
    }

    val datastore = if (testing) {
        DummyDatastore()
    } else {
        val driver = GraphDatabase.driver(config.neo4jUri, AuthTokens.basic(config.neo4jUser, config.neo4Password))
        driver.verifyConnectivity()
        Neo4jDatastore(driver)
    }

    val dataCollectorService = DataCollectorService(
        bigQueryClient = bigQueryClient,
        kafkaSender = kafkaSender,
        githubTokenProvider = githubTokenProvider,
        githubContentsClient = githubContentsClient,
        githubTreeClient = githubTreeClient,
    )
    val githubWebhookService = GithubWebhookHandler(config.repoChecks, githubContentsClient)

    //Start a timer to update every 24h
    Timer().scheduleAtFixedRate(timerTask {
        try {
            dataCollectorService.processDockerfileFeaturesAndSendToKafka()
        } catch (e: Exception) {
            dataCollectorService.logger.error("Scheduled job failed with unhandled exception", e)
        }
    }, calculateInitialDelayUntilClock(4, 0), TimeUnit.DAYS.toMillis(1))

    routing {
        get("/internal/isAlive") {
            if (!dataCollectorService.isAlive())
                call.respond(HttpStatusCode.ServiceUnavailable, "DataCollector service is not alive")
            call.respond(HttpStatusCode.OK, "OK")
        }

        val json = Json { ignoreUnknownKeys = true }
        post("/webhook/github") {
            val body = call.receiveText()
            val signature = call.request.headers["X-Hub-Signature-256"] ?: ""
            if (signature.isEmpty() || signature != generateHmac(body, config.githubWebhookSecret)) {
                call.respond(HttpStatusCode.Unauthorized, "Signature is invalid")
                return@post
            }

            val payload = try {
                json.decodeFromString<WebhookPayload>(body)
            } catch (_: SerializationException) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }

            launch {
                githubWebhookService.handleWebhookEvent(payload)
            }
            call.respond(HttpStatusCode.OK)

        }

        if (!testing) {
            metricsRoute()
        }
    }
}

private fun createGithubTokenProvider(config: ApplikasjonsConfig): GithubTokenProvider {
    if (config.hasGithubAppConfig) {
        return GithubAppAuth(
            appId = config.githubAppId!!,
            privateKeyContent = config.githubAppPrivateKey!!,
            installationId = config.githubAppInstallationId!!,
            userAgent = config.githubUserAgent,
        )
    }
    return StaticGithubTokenProvider(config.githubToken)
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
