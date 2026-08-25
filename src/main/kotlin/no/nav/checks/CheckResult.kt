package no.nav.checks

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class Severity {
    LOW, MEDIUM, HIGH
}

@Serializable
sealed class CheckResult {
    abstract val name: String
    abstract val desc: String
    abstract val severity: Severity
    abstract val whenChecked: Instant

    @Serializable
    @SerialName("AllGood")
    data class AllGood(override val name: String,
                       override val desc: String,
                       override val severity: Severity,
                       override val whenChecked: Instant) :
        CheckResult()

    @Serializable
    @SerialName("NeedsWork")
    data class NeedsWork(
        override val name: String,
        override val desc: String,
        override val severity: Severity,
        override val whenChecked: Instant,
        val reasons: List<String>
    ) : CheckResult()
}

@Serializable
data class CheckResultsForRepo(val repoName: String, val repoOwners: List<String>, val results : List<CheckResult>)
