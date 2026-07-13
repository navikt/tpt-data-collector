package no.nav

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import no.nav.config.ApplikasjonsConfig
import no.nav.datastore.DummyDatastore
import no.nav.datastore.Neo4jDatastore
import no.nav.github.FakeGitHub
import no.nav.github.GithubWebhookHandler
import no.nav.github.RealGitHub
import no.nav.github.WebhookPayload
import no.nav.metrics.metricsRoute
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module(testing: Boolean = false) {
    val config = ApplikasjonsConfig()

    val gitHub = if (testing) {
        FakeGitHub()
    } else {
        val httpClient = HttpClient(CIO)
        RealGitHub(httpClient, config.githubAppId!!, config.githubAppInstallationId!!, config.githubAppPrivateKey!!)
    }

    val datastore = if (testing) {
        DummyDatastore()
    } else {
        val driver = GraphDatabase.driver(config.neo4jUri, AuthTokens.basic(config.neo4jUser, config.neo4Password))
        driver.verifyConnectivity()
        Neo4jDatastore(driver)
    }

    val secretKey = SecretKeySpec(config.githubWebhookSecret.toByteArray(), "HmacSHA256")
    val mac = Mac.getInstance("HmacSHA256").also { it.init(secretKey) }

    val githubWebhookService = GithubWebhookHandler(gitHub, datastore)

    routing {
        get("/internal/isAlive") {
            call.respond(HttpStatusCode.OK, "OK")
        }

        // todo isReady

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
            call.respond(HttpStatusCode.OK)

        }

        if (!testing) {
            metricsRoute()
        }
    }
}

fun generateHmac(data: String, mac: Mac): String {
    val digest = mac.doFinal(data.toByteArray())
    return "sha256=${digest.fold("") { str, byte -> str + "%02x".format(byte) }}"
}
