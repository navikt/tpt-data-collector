@file:OptIn(ExperimentalSerializationApi::class)

package no.nav.github

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
class WebhookPayload (
    val ref: String,
    val after: String,
    val repository: Repository,
    val commits: List<Commit>
) {
}

@Serializable
class Repository (
    val id: Long,
    val name: String,
    @JsonNames("full_name")
    val fullName: String,
    @JsonNames("master_branch")
    val masterBranch: String,
)

@Serializable
class Commit(val id: String, val modified: List<String>, val added: List<String>, val removed: List<String>)
