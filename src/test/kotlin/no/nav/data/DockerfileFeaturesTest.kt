package no.nav.data

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DockerfileFeaturesTest {
    val dockerfileFeaturesJsonString = """
        [
            {
                "repo_id":"567189411","when_collected":"1768179606.3218479","file_type":"dockerfile",
                "content":"FROM ghcr.io/navikt/baseimages/temurin:21\nLABEL maintainer=\"Team Arbeidsforhold\"\n\nARG JAR_PATH\n\nADD ${'$'}JAR_PATH /app/app.jar",
                "path":"Dockerfile","uses_latest_tag":"false","has_user_instruction":"false","has_copy_sensitive":"false",
                "has_package_installs":"false","uses_multistage":"false","has_healthcheck":"false",
                "uses_add_instruction":"true","has_label_metadata":"true","has_expose":"false",
                "has_entrypoint_or_cmd":"false","installs_curl_or_wget":"false","installs_build_tools":"false",
                "has_apt_get_clean":"false","world_writable":"false","has_secrets_in_env_or_arg":"false"
            },
            {
                "repo_id":"584471087","when_collected":"1767574803.056072","file_type":"dockerfile",
                "content":"FROM navikt/java:17\nCOPY ./build/libs/rapids-and-rivers-grapher-all.jar app.jar",
                "path":"Dockerfile","uses_latest_tag":"false","has_user_instruction":"false","has_copy_sensitive":"false",
                "has_package_installs":"false","uses_multistage":"false","has_healthcheck":"false",
                "uses_add_instruction":"false","has_label_metadata":"false","has_expose":"false",
                "has_entrypoint_or_cmd":"false","installs_curl_or_wget":"false","installs_build_tools":"false",
                "has_apt_get_clean":"false","world_writable":"false","has_secrets_in_env_or_arg":"false"
            },
            {
                "repo_id":"608614179","when_collected":"1768179606.3218479","file_type":"dockerfile",
                "content":"FROM ghcr.io/navikt/baseimages/temurin:21\nLABEL maintainer=\"Team Arbeidsforhold\"\n\nARG JAR_PATH\n\nADD ${'$'}JAR_PATH /app/app.jar",
                "path":"Dockerfile","uses_latest_tag":"false","has_user_instruction":"false","has_copy_sensitive":"false",
                "has_package_installs":"false","uses_multistage":"false","has_healthcheck":"false",
                "uses_add_instruction":"true","has_label_metadata":"true","has_expose":"false",
                "has_entrypoint_or_cmd":"false","installs_curl_or_wget":"false","installs_build_tools":"false",
                "has_apt_get_clean":"false","world_writable":"false","has_secrets_in_env_or_arg":"false"
            }
        ]
    """.trimIndent()

    val reposJsonString = """
        [
            {"repo_id":"567189411","full_name":"org/cool_name"},
            {"repo_id":"584471087","full_name":"org/cooler_name"},
            {"repo_id":"608614179","full_name":"org/coolest_name"}
        ]
    """.trimIndent()

    @Test
    fun `Shall combine dockerfileFeatures and repoName`() {
        val dockerfileFeatures = DockerfileFeatures(
            dockerfileFeaturesList = Json.decodeFromString<List<Map<String, String>>>(dockerfileFeaturesJsonString),
            reposList = Json.decodeFromString<List<Map<String, String>>>(reposJsonString)
        )
        assertContains(dockerfileFeatures.toString(), "{\"repoId\":\"567189411\",\"repoName\":\"org/cool_name\"")
        assertContains(dockerfileFeatures.toString(), "{\"repoId\":\"584471087\",\"repoName\":\"org/cooler_name\"")
        assertContains(dockerfileFeatures.toString(), "{\"repoId\":\"608614179\",\"repoName\":\"org/coolest_name\"")
    }

    @Test
    fun `Shall determine if dockerfile uses distroless (not chainguard)`() {
        val dockerfile = """
            FROM gcr.io/distroless/java21-debian12@sha256:2bda49bc3f1dac94d4b8b2133545ab17f7be6c4216be8cea589d2d52660da308
            WORKDIR /app
            COPY build/libs/app-*.jar app.jar
            ENV JAVA_OPTS=\"-Dlogback.configurationFile=logback.xml\"
            ENV TZ=\"Europe/Oslo\"
            EXPOSE 8080
            USER nonroot
            CMD [ \"app.jar\" ]
        """.trimIndent()
        val dockerfileFeatures = DockerfileFeatures(
            dockerfileFeaturesList = Json.decodeFromString<List<Map<String, String>>>("""[{ "repo_id":"567189411","content": "$dockerfile"}]"""),
            reposList = Json.decodeFromString<List<Map<String, String>>>(reposJsonString),
        )
        assertFalse(dockerfileFeatures.dockerfileFeatures.first().usesChainguard)
        assertTrue(dockerfileFeatures.dockerfileFeatures.first().usesDistoless)
        assertEquals("gcr.io/distroless/java21-debian12", dockerfileFeatures.dockerfileFeatures.first().baseImage)
        assertTrue(dockerfileFeatures.dockerfileFeatures.first().pinsBaseImage)
    }

    @Test
    fun `Shall determine if dockerfile uses distroless (chainguard multistage)`() {
        val dockerfile = """
            FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25-dev AS builder
            WORKDIR /build
            COPY app/target/app.jar app.jar
            RUN java -Djarmode=tools -jar app.jar extract --launcher --layers --destination extracted
            
            FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21
            COPY --from=builder --chown=1069:1069 /build/extracted/snapshot-dependencies/ ./
            COPY --from=builder --chown=1069:1069 /build/extracted/spring-boot-loader/ ./
            COPY --from=builder --chown=1069:1069 /build/extracted/dependencies/ ./
            COPY --from=builder --chown=1069:1069 /build/extracted/application/ ./
            
            ENV TZ=\"Europe/Oslo\"
            CMD [\"-Dspring.profiles.active=nais\", \"-server\", \"-cp\", \".\", \"org.springframework.boot.loader.launch.JarLauncher\"]
        """.trimIndent()
        val dockerfileFeatures = DockerfileFeatures(
            dockerfileFeaturesList = Json.decodeFromString<List<Map<String, String>>>("""[{ "repo_id":"567189411","content": "$dockerfile"}]"""),
            reposList = Json.decodeFromString<List<Map<String, String>>>(reposJsonString),
        )
        assertTrue(dockerfileFeatures.dockerfileFeatures.first().usesChainguard)
        assertTrue(dockerfileFeatures.dockerfileFeatures.first().usesDistoless)
        assertEquals(
            "europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21",
            dockerfileFeatures.dockerfileFeatures.first().baseImage
        )
        assertFalse(dockerfileFeatures.dockerfileFeatures.first().pinsBaseImage)
    }

    @Test
    fun `Shall determine if dockerfile uses distroless (chainguard -dev)`() {
        val dockerfile = """
            FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25-dev AS builder
            WORKDIR /build
            COPY app/target/app.jar app.jar
            RUN java -Djarmode=tools -jar app.jar extract --launcher --layers --destination extracted
            
            FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21-dev
            COPY --from=builder --chown=1069:1069 /build/extracted/snapshot-dependencies/ ./
            COPY --from=builder --chown=1069:1069 /build/extracted/spring-boot-loader/ ./
            COPY --from=builder --chown=1069:1069 /build/extracted/dependencies/ ./
            COPY --from=builder --chown=1069:1069 /build/extracted/application/ ./
            
            ENV TZ=\"Europe/Oslo\"
            CMD [\"-Dspring.profiles.active=nais\", \"-server\", \"-cp\", \".\", \"org.springframework.boot.loader.launch.JarLauncher\"]
        """.trimIndent()
        val dockerfileFeatures = DockerfileFeatures(
            dockerfileFeaturesList = Json.decodeFromString<List<Map<String, String>>>("""[{ "repo_id":"567189411","content": "$dockerfile"}]"""),
            reposList = Json.decodeFromString<List<Map<String, String>>>(reposJsonString),
        )
        assertTrue(dockerfileFeatures.dockerfileFeatures.first().usesChainguard)
        assertFalse(dockerfileFeatures.dockerfileFeatures.first().usesDistoless)
        assertEquals(
            "europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21-dev",
            dockerfileFeatures.dockerfileFeatures.first().baseImage
        )
        assertFalse(dockerfileFeatures.dockerfileFeatures.first().pinsBaseImage)
    }

    @Test
    fun `Shall determine if dockerfile uses distroless (chainguard node nonslim)`() {
        val dockerfile = """
            FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/node:24-dev AS builder
            WORKDIR /app
            COPY . /app
            RUN npm ci
            RUN npm run build
            
            FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/node:24
            WORKDIR /app
            COPY --from=builder /app /app
            CMD [\"build/cli.js\", \"sync\"]
        """.trimIndent()
        val dockerfileFeatures = DockerfileFeatures(
            dockerfileFeaturesList = Json.decodeFromString<List<Map<String, String>>>("""[{ "repo_id":"567189411","content": "$dockerfile"}]"""),
            reposList = Json.decodeFromString<List<Map<String, String>>>(reposJsonString),
        )
        assertTrue(dockerfileFeatures.dockerfileFeatures.first().usesChainguard)
        assertFalse(dockerfileFeatures.dockerfileFeatures.first().usesDistoless)
        assertEquals(
            "europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/node:24",
            dockerfileFeatures.dockerfileFeatures.first().baseImage
        )
        assertFalse(dockerfileFeatures.dockerfileFeatures.first().pinsBaseImage)
    }

    @Test
    fun `Shall determine if dockerfile uses distroless (chainguard node-slim)`() {
        val dockerfile = """
            FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/node:24-dev AS builder
            WORKDIR /app
            COPY . /app
            RUN npm ci
            RUN npm run build
            
            FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/node:24-slim
            WORKDIR /app
            COPY --from=builder /app /app
            CMD [\"build/cli.js\", \"sync\"]
        """.trimIndent()
        val dockerfileFeatures = DockerfileFeatures(
            dockerfileFeaturesList = Json.decodeFromString<List<Map<String, String>>>("""[{ "repo_id":"567189411","content": "$dockerfile"}]"""),
            reposList = Json.decodeFromString<List<Map<String, String>>>(reposJsonString),
        )
        assertTrue(dockerfileFeatures.dockerfileFeatures.first().usesChainguard)
        assertTrue(dockerfileFeatures.dockerfileFeatures.first().usesDistoless)
        assertEquals(
            "europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/node:24-slim",
            dockerfileFeatures.dockerfileFeatures.first().baseImage
        )
        assertFalse(dockerfileFeatures.dockerfileFeatures.first().pinsBaseImage)
    }
}