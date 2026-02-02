package no.nav.kafka

import kotlinx.serialization.json.Json
import no.nav.data.DockerfileFeature
import no.nav.data.DockerfileFeatures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KafkaMessageFormatTest {

    private val reposJsonString = """
        [
            {"repo_id":"1","full_name":"navikt/tpt-data-collector"},
            {"repo_id":"2","full_name":"navikt/appsec-noob"}
        ]
    """.trimIndent()

    @Test
    fun `Kafka message jsonBlob should contain expected model structure`() {
        val dockerfileFeaturesJsonString = """
            [
                {
                    "repo_id":"1",
                    "when_collected":"1768179606.3218479",
                    "file_type":"dockerfile",
                    "content":"FROM cgr.dev/chainguard/tpt-data-collector/temurin:21@sha256:abc123\nLABEL maintainer=\"Team AppSec\"\nCOPY app.jar /app/app.jar",
                    "uses_multistage":"true"
                },
                {
                    "repo_id":"2",
                    "when_collected":"1768179606.3218479",
                    "file_type":"dockerfile",
                    "content":"FROM node/appsec-noob/bashandeverything:25@sha256:abc123"
                }
            ]
        """.trimIndent()

        // Use production code to generate jsonBlob exactly as it would be sent to Kafka
        val dockerfileFeatures = DockerfileFeatures(
            dockerfileFeaturesList = Json.decodeFromString<List<Map<String, String>>>(dockerfileFeaturesJsonString),
            reposList = Json.decodeFromString<List<Map<String, String>>>(reposJsonString)
        )
        val jsonBlob = dockerfileFeatures.toString()

        // Parse the jsonBlob back to verify structure
        val parsedRepos = Json.decodeFromString<List<DockerfileFeature>>(jsonBlob)
        assertEquals(2, parsedRepos.size)

        val distroless = parsedRepos.first { it.repoId == "1" }
        assertEquals("navikt/tpt-data-collector", distroless.repoName)
        assertEquals(true, distroless.usesDistroless)

        val nonDistroless = parsedRepos.first { it.repoId == "2" }
        assertEquals("navikt/appsec-noob", nonDistroless.repoName)
        assertEquals(false, nonDistroless.usesDistroless)
    }
}
