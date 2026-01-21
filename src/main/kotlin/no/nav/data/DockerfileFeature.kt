package no.nav.data

import kotlinx.serialization.Serializable

@Serializable
data class DockerfileFeature(
    val repoId: String,
    val repoName: String,
    val fileType: String,
    val usesMultistage: Boolean,
    val usesChainguard: Boolean,
    val usesDistroless: Boolean,
)