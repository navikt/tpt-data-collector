package no.nav

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Date
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import no.nav.checks.CheckResultsForRepo
import no.nav.config.ApplikasjonsConfig
import no.nav.datastore.FakeDatastore
import no.nav.github.FakeGitHub
import no.nav.github.GitHubSyncEvent
import no.nav.tpt.FakeTptBackend
import no.nav.whodis.FakeWhodis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class TriggerEndpointsTest {
    private val issuer = "https://issuer.example"
    private val audience = "tpt-data-collector-client-id"
    private val signingKey = RSAKeyGenerator(2048).keyID("test-key").generate()

    @TempDir
    lateinit var tempDir: Path

    private fun config(): ApplikasjonsConfig {
        val jwks = tempDir.resolve("jwks.json")
        Files.writeString(jwks, JWKSet(signingKey.toPublicJWK()).toString())
        return ApplikasjonsConfig(
            githubWebhookSecret = "dummy",
            githubOrganization = "navikt",
            openIdIssuer = issuer,
            openIdAudience = audience,
            openIdJwksUri = jwks.toUri().toString(),
        )
    }

    private fun token(): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject("tpt-backend")
            .expirationTime(Date(System.currentTimeMillis() + 60_000))
            .build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.keyID).build(), claims)
            .apply { sign(RSASSASigner(signingKey)) }
            .serialize()
    }

    @Test
    fun `team trigger answers 202 and sends check results as callbacks`() = testApplication {
        val received = CompletableDeferred<CheckResultsForRepo>()
        val backend = object : FakeTptBackend() {
            override suspend fun sendCheckResults(results: CheckResultsForRepo): Boolean {
                received.complete(results)
                return super.sendCheckResults(results)
            }
        }
        val gitHub = object : FakeGitHub() {
            override suspend fun allReposForTeam(teamName: String) = listOf("some-repo")
        }
        application { businessModule(gitHub, FakeDatastore(), backend, FakeWhodis(), config()) }

        val response = client.post("/team/my-team") { bearerAuth(token()) }

        assertEquals(HttpStatusCode.Accepted, response.status)
        val results = withTimeout(10.seconds) { received.await() }
        assertEquals("some-repo", results.repoName)
        assertEquals(listOf("my-team"), results.repoOwners)
    }

    @Test
    fun `team trigger answers 400 for an invalid slug`() = testApplication {
        application { businessModule(FakeGitHub(), FakeDatastore(), FakeTptBackend(), FakeWhodis(), config()) }

        val response = client.post("/team/Not_A_Slug") { bearerAuth(token()) }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `github collect answers 202 and always ends with sync complete`() = testApplication {
        val completed = CompletableDeferred<GitHubSyncEvent>()
        val backend = object : FakeTptBackend() {
            override suspend fun sendSyncComplete(event: GitHubSyncEvent): Boolean {
                completed.complete(event)
                return super.sendSyncComplete(event)
            }
        }
        application { businessModule(FakeGitHub(), FakeDatastore(), backend, FakeWhodis(), config()) }

        val response = client.post("/collect/github") {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody("""{"teams":["my-team"]}""")
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals(listOf("my-team"), withTimeout(10.seconds) { completed.await() }.teams)
    }

    @Test
    fun `github collect answers 400 for bad input`() = testApplication {
        application { businessModule(FakeGitHub(), FakeDatastore(), FakeTptBackend(), FakeWhodis(), config()) }

        listOf("not json", "{}", """{"teams":[],"repositories":[]}""").forEach { body ->
            val response = client.post("/collect/github") {
                bearerAuth(token())
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "body: $body")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["navikt", "another-org"])
    fun `github webhook answers 202 and sends check results for the configured organization`(organization: String) = testApplication {
        val received = CompletableDeferred<CheckResultsForRepo>()
        val backend = object : FakeTptBackend() {
            override suspend fun sendCheckResults(results: CheckResultsForRepo): Boolean {
                received.complete(results)
                return super.sendCheckResults(results)
            }
        }
        application {
            businessModule(FakeGitHub(), FakeDatastore(), backend, FakeWhodis(), ApplikasjonsConfig(githubWebhookSecret = "dummy", githubOrganization = organization))
        }

        val body = Files.readString(Paths.get("src/test/resources/github_push_webhook.json"))
            .replace("navikt/", "$organization/")
        val signature = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec("dummy".toByteArray(), "HmacSHA256"))
        }.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
        val response = client.post("/webhook/github") {
            contentType(ContentType.Application.Json)
            setBody(body)
            header("X-Hub-Signature-256", "sha256=$signature")
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        val results = withTimeout(10.seconds) { received.await() }
        assertEquals("tpt-data-collector", results.repoName)
        assertEquals(listOf("tulleteam"), results.repoOwners)
    }
}
