@file:OptIn(ExperimentalSerializationApi::class)

package no.nav.github

import com.google.errorprone.annotations.Modifier
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
class WebhookPayload (
    val ref: String,
    val pusher: Pusher,
    val repository: Repository,
    val commits: List<Commit>
) {
}

@Serializable
class Pusher (
    val name: String,
)

@Serializable
class Repository (
    val name: String,
    @JsonNames("full_name")
    val fullName: String,
    @JsonNames("master_branch")
    val masterBranch: String,
)

@Serializable
class Commit(val modified: List<String>, val added: List<String>, val removed: List<String>)


