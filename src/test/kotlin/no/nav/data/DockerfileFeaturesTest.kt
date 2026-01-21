package no.nav.data

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertContains

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
}