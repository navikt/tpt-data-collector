package no.nav.github

import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class FileContentsResponse(
    @SerialName("content")
    val content: String
) {
    fun decode() = Base64.decode(content.replace("\n", "")).decodeToString()
}

@Serializable
internal data class TokenExchangeResponse(
    @SerialName("token")
    val token: String,
    @SerialName("expires_at")
    val expiresAt: Instant
)

@Serializable
internal data class DependabotAlert(
    @SerialName("security_advisory")
    val advisory: SecurityAdvisory
)

@Serializable
data class GithubCodeScanningAnalysis(
    @SerialName("category") val category: String,
    @SerialName("tool") val tool: CodeScanningTool,
    @SerialName("error") val error: String,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("url") val url: String,
)

@Serializable
data class CodeScanningTool(
    @SerialName("name") val name: String,
)

@Serializable
internal data class SecurityAdvisory(
    @SerialName("vulnerabilities")
    val vulnerabilities: List<Vulnerability>
)

@Serializable
internal data class Vulnerability(
    @SerialName("package")
    val pkg: Package,
    @SerialName("severity")
    val severity: String
)

@Serializable
internal data class Package(
    @SerialName("ecosystem")
    val ecosystem: String,
    @SerialName("name")
    val name: String
)

internal data class AccessToken(val value: String, val expiry: Instant)

@Serializable
internal data class RepoRootResponse(
    @SerialName("default_branch")
    val defaultBranch: String
)

@Serializable
internal data class TreeResponse(
    @SerialName("tree")
    val tree: List<TreeEntry>
)

@Serializable
internal data class TreeEntry(
    @SerialName("path")
    val path: String,
)

@Serializable
internal data class ReposForTeamResponse(val name: String)