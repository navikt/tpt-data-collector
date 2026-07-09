package no.nav

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
import no.nav.github.DummyGithubRepositoryClient
import no.nav.github.GithubApiClient
import no.nav.github.GithubAppAuth
import no.nav.github.GithubRepositoryContentsClient
import no.nav.github.GithubTokenProvider
import no.nav.github.GithubWebhookHandler
import no.nav.github.StaticGithubTokenProvider
import no.nav.github.WebhookPayload
import no.nav.metrics.metricsRoute
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module(testing: Boolean = false) {
    val config = ApplikasjonsConfig()

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

    val datastore = if (testing) {
        DummyDatastore()
    } else {
        val driver = GraphDatabase.driver(config.neo4jUri, AuthTokens.basic(config.neo4jUser, config.neo4Password))
        driver.verifyConnectivity()
        Neo4jDatastore(driver)
    }

    val secretKey = SecretKeySpec(config.githubWebhookSecret.toByteArray(), "HmacSHA256")
    val mac = Mac.getInstance("HmacSHA256").also { it.init(secretKey) }

    val githubWebhookService = GithubWebhookHandler(githubContentsClient, datastore)

    routing {
        get("/internal/isAlive") {
            call.respond(HttpStatusCode.OK, "OK")
        }

        // todo isalive

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

fun generateHmac(data: String, mac: Mac): String {
    val digest = mac.doFinal(data.toByteArray())
    return "sha256=${digest.fold("") { str, byte -> str + "%02x".format(byte) }}"
}
