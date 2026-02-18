@file:OptIn(ExperimentalSerializationApi::class)

package no.nav.github

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
class WebhookEvent (
    @JsonNames("event")
    val type: String,
    val payload: Payload,
)

@Serializable
class Payload (
    val ref: String,
    val pusher: Pusher,
    val repository: Repository,
)

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
