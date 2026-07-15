package no.nav

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpStatusCode.Companion.InternalServerError
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.logging.LogbackMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import no.nav.config.ApplikasjonsConfig
import no.nav.datastore.Datastore
import no.nav.datastore.Neo4jDatastore
import no.nav.github.GitHub
import no.nav.github.GithubWebhookHandler
import no.nav.github.RealGitHub
import no.nav.github.WebhookPayload
import no.nav.kafka.KafkaSender
import no.nav.kafka.KafkaSenderInterface
import no.nav.metrics.TPTMetrics
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase

fun main() {
    val config = ApplikasjonsConfig()
    embeddedServer(Netty, port = 8080) {
        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        val gitHub =
            RealGitHub(httpClient, config.githubAppId!!, config.githubAppInstallationId!!, config.githubAppPrivateKey!!)

        val driver = GraphDatabase.driver(config.neo4jUri, AuthTokens.basic(config.neo4jUser, config.neo4Password))
        driver.verifyConnectivity()
        val dataStore = Neo4jDatastore(driver)

        val kafka = KafkaSender()

        businessModule(gitHub, dataStore, config.githubWebhookSecret, kafka)
        naisModule(gitHub, dataStore)
    }.start(wait = true)
}

fun Application.businessModule(gitHub: GitHub, datastore: Datastore, webhookSecret: String, kafka: KafkaSenderInterface) {
    val secretKey = SecretKeySpec(webhookSecret.toByteArray(), "HmacSHA256")
    val mac = Mac.getInstance("HmacSHA256").also { it.init(secretKey) }

    val githubWebhookService = GithubWebhookHandler(gitHub, datastore, kafka)

    routing {
        val json = Json { ignoreUnknownKeys = true }
        post("/webhook/github") {
            val body = call.receiveText()
            val signature = call.request.headers["X-Hub-Signature-256"] ?: ""
            if (signature.isEmpty() || signature != generateHmac(body, mac)) {
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
            call.respond(OK)

        }

    }
}

fun Application.naisModule(gitHub: GitHub, datastore: Datastore) {
    install(MicrometerMetrics) {
        registry = TPTMetrics.registry
        meterBinders = listOf(
            LogbackMetrics(),
            JvmGcMetrics(),
            JvmMemoryMetrics(),
            JvmThreadMetrics(),
            ProcessorMetrics(),
            UptimeMetrics(),
        )
    }

    routing {
        get("/internal/isAlive") {
            call.respond(OK, "OK")
        }

        get("/internal/isReady") {
            try {
                val status = if (gitHub.ping() && datastore.ping()) OK else InternalServerError
                call.respond(status)
            } catch (ex: Exception) {
                ex.printStackTrace()
                call.respond(InternalServerError)
            }
        }

        get("/internal/metrics") {
            call.respond(TPTMetrics.registry.scrape())
        }
    }
}

fun generateHmac(data: String, mac: Mac): String {
    val digest = mac.doFinal(data.toByteArray())
    return "sha256=${digest.fold("") { str, byte -> str + "%02x".format(byte) }}"
}


