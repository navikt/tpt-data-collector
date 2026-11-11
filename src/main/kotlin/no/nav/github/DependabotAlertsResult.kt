package no.nav.github

sealed interface DependabotAlertsResult {
    data class Available(val alerts: Map<String, String>) : DependabotAlertsResult
    data object Disabled : DependabotAlertsResult
}
