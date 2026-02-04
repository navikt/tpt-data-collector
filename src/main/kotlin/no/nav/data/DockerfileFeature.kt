package no.nav.data

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DockerfileFeature(
    val repoId: String,
    val repoName: String,
    val whenCollected: LocalDateTime?,
    val fileType: String,
    val baseImage: String,
    val pinsBaseImage: Boolean,
    val usesMultistage: Boolean,
    val usesChainguard: Boolean,
    val usesDistroless: Boolean,
) {
    fun toJson(): String {
        return Json.encodeToString(this)
    }
}