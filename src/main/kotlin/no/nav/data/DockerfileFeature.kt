package no.nav.data

import kotlinx.serialization.Serializable

@Serializable
data class DockerfileFeature(
    val repoId: String,
    val repoName: String,
    val fileType: String,
    val baseImage: String,
    val pinsBaseImage: Boolean,
    val usesMultistage: Boolean,
    val usesChainguard: Boolean,
    val usesDistoless: Boolean,
)